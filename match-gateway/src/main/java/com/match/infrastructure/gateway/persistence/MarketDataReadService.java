// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import com.match.domain.MarketInfo;
import com.match.infrastructure.gateway.state.Candle;
import com.match.infrastructure.gateway.state.GatewayStateManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Async DB-first reads for candle history and the recent-trades tape.
 * The database is the source of truth; the in-memory rings are the fallback
 * when it is unavailable, times out, or errors. A short JSON cache absorbs
 * WebSocket connect/resync storms (the resync-after-drain path re-requests
 * initial state, which would otherwise hammer Postgres).
 *
 * Never called on the egress thread; Netty event loops only receive completed
 * futures (queries run on the dedicated read pool).
 */
public final class MarketDataReadService implements AutoCloseable {

    private static final long CACHE_TTL_MS = 2_000;
    private static final long READ_TIMEOUT_MS = 1_500;

    private record CachedJson(String json, long atMs) {}

    private final MarketDataDb db;
    private final TimescaleReader reader;
    private final ExecutorService readPool;
    private final ConcurrentHashMap<String, CachedJson> cache = new ConcurrentHashMap<>();

    public MarketDataReadService(MarketDataDb db, TimescaleReader reader) {
        this.db = db;
        this.reader = reader;
        this.readPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "market-pg-read");
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<String> candleHistoryJson(int marketId, String interval, int limit,
                                                       Supplier<String> memoryFallback) {
        if (!db.isReady() || !TimescaleReader.supportsInterval(interval)) {
            db.readFallbacks.incrementAndGet();
            return CompletableFuture.completedFuture(memoryFallback.get());
        }
        return cachedRead("c:" + marketId + ":" + interval + ":" + limit, memoryFallback, () -> {
            List<Candle> candles = reader.getCandles(marketId, interval, limit);
            return GatewayStateManager.candleHistoryJson(marketId, marketName(marketId), interval, candles);
        });
    }

    public CompletableFuture<String> recentTradesJson(int limit, int marketId,
                                                      Supplier<String> memoryFallback) {
        if (!db.isReady()) {
            db.readFallbacks.incrementAndGet();
            return CompletableFuture.completedFuture(memoryFallback.get());
        }
        return cachedRead("t:" + marketId + ":" + limit, memoryFallback, () -> {
            List<TimescaleReader.TapeRow> rows = reader.getRecentTrades(limit, marketId);
            // Mirror TradeRingBuffer.toJsonForMarket: marketId 0 resolves to the newest trade's market
            int resolvedId = marketId > 0 ? marketId : (rows.isEmpty() ? 0 : rows.get(0).marketId());
            return GatewayStateManager.tradesBatchJson(resolvedId, marketName(resolvedId), rows);
        });
    }

    private interface SqlSupplier {
        String get() throws Exception;
    }

    private CompletableFuture<String> cachedRead(String key, Supplier<String> memoryFallback,
                                                 SqlSupplier query) {
        CachedJson cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.atMs() < CACHE_TTL_MS) {
            return CompletableFuture.completedFuture(cached.json());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = query.get();
                cache.put(key, new CachedJson(json, System.currentTimeMillis()));
                return json;
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, readPool).orTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).exceptionally(e -> {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (!(cause instanceof TimeoutException)) {
                db.markDown("read: " + cause.getMessage());
            }
            db.readFallbacks.incrementAndGet();
            return memoryFallback.get();
        });
    }

    private static String marketName(int marketId) {
        MarketInfo info = MarketInfo.fromId(marketId);
        return info != null ? info.symbol() : "UNKNOWN";
    }

    @Override
    public void close() {
        readPool.shutdownNow();
    }
}
