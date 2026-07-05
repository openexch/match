// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import com.match.domain.MarketInfo;
import com.match.infrastructure.gateway.state.GatewayStateManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Computes true rolling-24h ticker figures from the 1m candle aggregate and
 * pushes them into each market's TickerStats every 5 seconds. This replaces
 * the legacy since-boot "24h" semantics whenever the database is healthy.
 *
 * Doubles as the availability re-probe: when the database is marked down and
 * the write queue is idle, nothing else touches Postgres — this service keeps
 * attempting (at a reduced cadence) and flips the flag back up on recovery.
 */
public final class RollingTickerService implements AutoCloseable {

    private static final long REFRESH_PERIOD_SECONDS = 5;
    private static final int DOWN_PROBE_EVERY_N_TICKS = 6; // ~30s while down

    private final MarketDataDb db;
    private final TimescaleReader reader;
    private final GatewayStateManager stateManager;
    private final ScheduledExecutorService scheduler;
    private int ticksWhileDown = 0;

    public RollingTickerService(MarketDataDb db, TimescaleReader reader,
                                GatewayStateManager stateManager) {
        this.db = db;
        this.reader = reader;
        this.stateManager = stateManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-pg-ticker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, REFRESH_PERIOD_SECONDS,
                REFRESH_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** Startup hydration: one synchronous refresh (bounded by the pool's 3s connect timeout). */
    public void refreshNowBlocking() {
        if (db.isReady()) {
            refreshAll();
        }
    }

    private void tick() {
        if (!db.isAvailable()) {
            // Reduced-cadence probe while down; also re-runs the schema
            // bootstrap after a cold boot against a dead/fresh database.
            if (++ticksWhileDown % DOWN_PROBE_EVERY_N_TICKS != 0) {
                return;
            }
        }
        ticksWhileDown = 0;
        if (!db.isSchemaReady() && !db.ensureSchema()) {
            return;
        }
        refreshAll();
    }

    /** Queries unconditionally — a success while marked down is what flips the flag back up. */
    private void refreshAll() {
        for (MarketInfo market : MarketInfo.ALL) {
            try {
                TickerBaseline baseline = reader.get24hBaseline(market.id());
                db.markUp();
                if (baseline.hasData()) {
                    stateManager.applyTickerBaseline(market.id(), baseline);
                }
            } catch (Exception e) {
                db.markDown("ticker: " + e.getMessage());
                return; // remaining markets would fail the same way
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
