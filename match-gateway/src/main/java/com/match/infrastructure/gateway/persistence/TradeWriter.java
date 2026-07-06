// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Write-behind persister for the raw trade stream.
 *
 * The egress (main polling) thread only calls {@link #offer} — lock-free,
 * never blocks; on a full queue the trade is dropped and counted (uptime and
 * latency beat completeness on this plane). A single daemon thread drains the
 * queue and batch-inserts into the {@code trades} hypertable.
 *
 * Failure semantics:
 * - unreachable database (connect failure): batch is RETAINED and retried with
 *   exponential backoff — the queue becomes the outage buffer, drained on recovery
 * - statement failure on a healthy connection: retried once, then the batch is
 *   dropped and counted in writeErrors (a poison batch must not wedge the writer)
 */
public final class TradeWriter implements Runnable, AutoCloseable {

    /** {@code takerIsBuy}: TRUE = taker bought, FALSE = taker sold, null = unknown (pre-v5 upstream). */
    record TradeRow(int marketId, long tsMs, double price, double quantity, int tradeCount,
                    Boolean takerIsBuy) {}

    /** Seam for tests: production uses the JDBC sink below. */
    interface BatchSink {
        /**
         * @throws SQLTransientException when the database is unreachable (retain the batch)
         * @throws SQLException          on statement failure (counts against the batch)
         */
        void write(List<TradeRow> batch) throws SQLException;
    }

    private static final String INSERT_SQL =
            "INSERT INTO trades (time, market_id, price, quantity, trade_count, taker_side)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";

    private static final long IDLE_PARK_NANOS = 50L * 1_000_000;
    private static final long LINGER_PARK_NANOS = 10L * 1_000_000;
    private static final long BACKOFF_MIN_MS = 250;
    private static final long BACKOFF_MAX_MS = 10_000;

    private final MarketDataDb db;
    private final BatchSink sink;
    private final OneToOneConcurrentArrayQueue<TradeRow> queue;
    private final int maxBatch;
    private final long lingerMs;
    private final boolean bootstrapSchema;
    private final Thread thread;
    private volatile boolean running = true;

    public TradeWriter(MarketDataDb db) {
        this(db, null, 65_536, 512, 200, true);
    }

    TradeWriter(MarketDataDb db, BatchSink sink, int capacity, int maxBatch, long lingerMs,
                boolean bootstrapSchema) {
        this.db = db;
        this.sink = sink != null ? sink : this::writeJdbcBatch;
        this.queue = new OneToOneConcurrentArrayQueue<>(capacity);
        this.maxBatch = maxBatch;
        this.lingerMs = lingerMs;
        this.bootstrapSchema = bootstrapSchema;
        this.thread = new Thread(this, "market-pg-writer");
        this.thread.setDaemon(true);
        db.setQueueDepthSupplier(queue::size);
    }

    /**
     * Hot path — called ONLY from the egress (main polling) thread.
     * Lock-free, never blocks; drops and counts when the queue is full.
     */
    public boolean offer(int marketId, double price, double quantity, int tradeCount, long tsMs,
                         Boolean takerIsBuy) {
        if (queue.offer(new TradeRow(marketId, tsMs, price, quantity, tradeCount, takerIsBuy))) {
            return true;
        }
        long dropped = db.tradesDropped.incrementAndGet();
        if (dropped == 1 || dropped % 1000 == 0) {
            System.out.println("[market-pg] write queue full, dropped " + dropped + " trade(s) so far");
        }
        return false;
    }

    public int queueDepth() {
        return queue.size();
    }

    public void start() {
        thread.start();
    }

    @Override
    public void run() {
        if (bootstrapSchema) {
            db.ensureSchema();
        }
        final List<TradeRow> batch = new ArrayList<>(maxBatch);
        long batchStartMs = 0;
        long backoffMs = 0;
        int statementFailures = 0;

        while (running || !queue.isEmpty() || !batch.isEmpty()) {
            TradeRow row;
            while (batch.size() < maxBatch && (row = queue.poll()) != null) {
                if (batch.isEmpty()) {
                    batchStartMs = System.currentTimeMillis();
                }
                batch.add(row);
            }
            if (batch.isEmpty()) {
                if (!running) {
                    break;
                }
                LockSupport.parkNanos(IDLE_PARK_NANOS);
                continue;
            }
            boolean due = batch.size() >= maxBatch
                    || System.currentTimeMillis() - batchStartMs >= lingerMs
                    || !running;
            if (!due) {
                LockSupport.parkNanos(LINGER_PARK_NANOS);
                continue;
            }
            if (backoffMs > 0) {
                LockSupport.parkNanos(backoffMs * 1_000_000);
            }
            try {
                sink.write(batch);
                db.tradesWritten.addAndGet(batch.size());
                db.batchFlushes.incrementAndGet();
                db.markUp();
                batch.clear();
                statementFailures = 0;
                backoffMs = 0;
            } catch (SQLTransientException e) {
                db.markDown("write: " + e.getMessage());
                if (!running) {
                    break; // shutting down — don't spin on an unreachable DB
                }
                backoffMs = nextBackoff(backoffMs);
            } catch (SQLException e) {
                statementFailures++;
                if (statementFailures >= 2) {
                    db.writeErrors.incrementAndGet();
                    System.out.println("[market-pg] dropping batch of " + batch.size()
                            + " after repeated statement failure: " + e.getMessage());
                    batch.clear();
                    statementFailures = 0;
                    backoffMs = 0;
                } else {
                    backoffMs = nextBackoff(backoffMs);
                }
            }
        }
    }

    private static long nextBackoff(long current) {
        return current == 0 ? BACKOFF_MIN_MS : Math.min(current * 2, BACKOFF_MAX_MS);
    }

    private void writeJdbcBatch(List<TradeRow> batch) throws SQLException {
        // Cold boot against a dead/fresh database: the schema must exist
        // before the first insert. Unreachable-DB semantics (retain batch).
        if (!db.isSchemaReady() && !db.ensureSchema()) {
            throw new SQLTransientException("schema not ready");
        }
        Connection conn;
        try {
            conn = db.getConnection();
        } catch (SQLException e) {
            throw new SQLTransientException("connect: " + e.getMessage(), e);
        }
        try (conn) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                for (TradeRow row : batch) {
                    ps.setTimestamp(1, new Timestamp(row.tsMs()));
                    ps.setInt(2, row.marketId());
                    ps.setDouble(3, row.price());
                    ps.setDouble(4, row.quantity());
                    ps.setInt(5, row.tradeCount());
                    // null-safe: setObject writes SQL NULL for unknown taker side
                    ps.setObject(6, row.takerIsBuy(), java.sql.Types.BOOLEAN);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    /** Stop the writer; attempts a final drain (bounded by the loop's shutdown guards). */
    @Override
    public void close() {
        running = false;
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
