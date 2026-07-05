// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Connection pool + schema bootstrap + availability tracking for the
 * market-data TimescaleDB. The pool is constructed lazily-tolerant
 * (initializationFailTimeout = -1): a dead Postgres never fails or delays
 * gateway boot — uptime is priority 1. Availability transitions are logged
 * once per flip, never per failure.
 */
public final class MarketDataDb implements AutoCloseable {

    private static final String SCHEMA_RESOURCE = "/sql/marketdata-schema.sql";

    private final HikariDataSource dataSource;
    private volatile boolean available = false;
    private volatile boolean schemaReady = false;

    // /metrics counters (match#33 style: plain atomics, hand-rendered in GatewayHttpHandler)
    public final AtomicLong tradesWritten = new AtomicLong();
    public final AtomicLong tradesDropped = new AtomicLong();
    public final AtomicLong writeErrors = new AtomicLong();
    public final AtomicLong batchFlushes = new AtomicLong();
    public final AtomicLong readFallbacks = new AtomicLong();
    private volatile IntSupplier queueDepth = () -> 0;

    private MarketDataDb(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Never throws for "DB down": the pool retries internally on each getConnection(). */
    public static MarketDataDb create(MarketDataDbConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.url());
        hc.setUsername(cfg.user());
        hc.setPassword(cfg.password());
        hc.setMaximumPoolSize(4);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(3_000);
        hc.setValidationTimeout(2_000);
        hc.setKeepaliveTime(30_000);
        hc.setInitializationFailTimeout(-1);
        hc.setPoolName("market-pg");
        hc.addDataSourceProperty("socketTimeout", "10");
        hc.addDataSourceProperty("loginTimeout", "3");
        hc.addDataSourceProperty("ApplicationName", "market-gateway");
        return new MarketDataDb(new HikariDataSource(hc));
    }

    public Connection getConnection() throws java.sql.SQLException {
        return dataSource.getConnection();
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isSchemaReady() {
        return schemaReady;
    }

    public boolean isReady() {
        return available && schemaReady;
    }

    public void markUp() {
        if (!available) {
            available = true;
            System.out.println("[market-pg] database UP");
        }
    }

    public void markDown(String reason) {
        if (available) {
            available = false;
            System.out.println("[market-pg] database DOWN: " + reason);
        }
    }

    public void setQueueDepthSupplier(IntSupplier supplier) {
        this.queueDepth = supplier;
    }

    /** Test seam (package-private): pretend the schema bootstrap succeeded. */
    void markSchemaReadyForTests() {
        schemaReady = true;
    }

    public int queueDepth() {
        return queueDepth.getAsInt();
    }

    /**
     * Apply the idempotent schema (hypertable + continuous aggregates + policies).
     * Safe to call repeatedly and from multiple threads; cheap once applied.
     * Statements run with autocommit — continuous aggregates cannot be created
     * inside a transaction block. Returns false (and marks down) on any failure.
     */
    public synchronized boolean ensureSchema() {
        if (schemaReady) {
            return true;
        }
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'")) {
                if (!rs.next()) {
                    markDown("timescaledb extension not installed in this database (provision it as superuser)");
                    return false;
                }
            }
            for (String sql : loadSchemaStatements()) {
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
            }
            schemaReady = true;
            markUp();
            System.out.println("[market-pg] schema ensured");
            return true;
        } catch (Exception e) {
            markDown("schema bootstrap failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Load the reference SQL, strip whole-line comments, split on ';'.
     * The schema file deliberately avoids inline comments and semicolons
     * inside comments so this split is safe.
     */
    static List<String> loadSchemaStatements() {
        String raw;
        try (InputStream in = MarketDataDb.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + SCHEMA_RESOURCE);
            }
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to read " + SCHEMA_RESOURCE, e);
        }
        StringBuilder stripped = new StringBuilder(raw.length());
        for (String line : raw.split("\n", -1)) {
            if (!line.strip().startsWith("--")) {
                stripped.append(line).append('\n');
            }
        }
        List<String> statements = new ArrayList<>();
        for (String part : stripped.toString().split(";")) {
            String sql = part.strip();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
