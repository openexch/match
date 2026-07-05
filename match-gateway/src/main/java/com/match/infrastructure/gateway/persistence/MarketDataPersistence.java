// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import com.match.domain.MarketInfo;
import com.match.infrastructure.gateway.state.Candle;
import com.match.infrastructure.gateway.state.GatewayStateManager;
import com.match.infrastructure.gateway.state.InMemoryCandleProvider;

import java.util.List;

/**
 * Facade over the market-data persistence layer (TimescaleDB).
 * The database is the source of truth for chart/time-series data; the
 * in-memory rings exist for the live push path and as the fallback when
 * the database is unavailable. Absent configuration disables the whole
 * layer and the gateway behaves exactly as before persistence existed.
 */
public final class MarketDataPersistence implements AutoCloseable {

    private final MarketDataDb db;
    private final TradeWriter writer;
    private final TimescaleReader reader;
    private final MarketDataReadService reads;
    private final RollingTickerService ticker;

    private MarketDataPersistence(MarketDataDb db, TradeWriter writer, TimescaleReader reader,
                                  MarketDataReadService reads, RollingTickerService ticker) {
        this.db = db;
        this.writer = writer;
        this.reader = reader;
        this.reads = reads;
        this.ticker = ticker;
    }

    /**
     * Build and start the persistence layer, or return null when disabled by
     * environment (no MARKET_PG_PASSWORD, or MARKET_PG_ENABLED=false).
     * Never throws and never blocks on a dead database.
     */
    public static MarketDataPersistence startOrNull(GatewayStateManager stateManager) {
        MarketDataDbConfig cfg = MarketDataDbConfig.fromEnv();
        if (!cfg.enabled()) {
            System.out.println("[market-pg] persistence disabled (no MARKET_PG_PASSWORD or MARKET_PG_ENABLED=false)");
            return null;
        }
        MarketDataDb db = MarketDataDb.create(cfg);
        TimescaleReader reader = new TimescaleReader(db);
        TradeWriter writer = new TradeWriter(db);
        MarketDataReadService reads = new MarketDataReadService(db, reader);
        RollingTickerService ticker = new RollingTickerService(db, reader, stateManager);
        writer.start();
        System.out.println("[market-pg] persistence enabled: " + cfg.url());
        return new MarketDataPersistence(db, writer, reader, reads, ticker);
    }

    public TradeWriter writer() {
        return writer;
    }

    public MarketDataReadService reads() {
        return reads;
    }

    public MarketDataDb db() {
        return db;
    }

    /**
     * Seed the in-memory rings and ticker from the database, then start the
     * periodic ticker refresh. MUST run before egress polling starts (the
     * candle rings are single-writer). A dead database only costs the pool's
     * 3s connect timeout — boot never depends on Postgres.
     */
    public void hydrate(InMemoryCandleProvider candleProvider) {
        try {
            long startMs = System.currentTimeMillis();
            if (db.ensureSchema()) {
                int seeded = 0;
                for (MarketInfo market : MarketInfo.ALL) {
                    for (String interval : InMemoryCandleProvider.INTERVALS) {
                        List<Candle> candles = reader.getCandles(market.id(), interval, 500);
                        candleProvider.seed(market.id(), interval, candles);
                        seeded += candles.size();
                    }
                }
                ticker.refreshNowBlocking();
                System.out.println("[market-pg] hydrated " + seeded + " candles in "
                        + (System.currentTimeMillis() - startMs) + "ms");
            } else {
                System.out.println("[market-pg] hydration skipped (database unavailable)");
            }
        } catch (Exception e) {
            db.markDown("hydrate: " + e.getMessage());
            System.out.println("[market-pg] hydration failed, continuing in-memory: " + e.getMessage());
        } finally {
            ticker.start(); // also the recovery probe when the DB is down
        }
    }

    @Override
    public void close() {
        ticker.close();
        reads.close();
        writer.close(); // final drain while the pool is still open
        db.close();
    }
}
