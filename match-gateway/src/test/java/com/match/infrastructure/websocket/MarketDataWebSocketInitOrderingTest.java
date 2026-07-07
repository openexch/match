// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.websocket;

import com.match.infrastructure.gateway.state.GatewayOrderBook;
import com.match.infrastructure.gateway.state.GatewayStateManager;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.*;

/**
 * Ordering tests for the subscribe initial-state / live-broadcast race (openexch/match#97).
 *
 * On subscribe the channel joins the market broadcast group synchronously, but the initial
 * trades and candle-history snapshots are fetched asynchronously (a DB round trip). Live
 * TRADES_BATCH/CANDLE_UPDATE frames arriving in that window used to be delivered BEFORE the
 * older "initial" snapshot, so a client rendering each batch as current state showed a trade
 * then dropped it (the tape appeared to rewind). The fix buffers those live frames per-channel
 * until the async snapshot flushes, then replays them in arrival order.
 *
 * These tests drive the real {@link MarketDataWebSocket.MarketDataHandler} through an
 * EmbeddedChannel with a state manager whose async fetches complete on demand, so the race
 * window is deterministic. EmbeddedChannel.inEventLoop() is always true (writes land in the
 * outbound queue inline) while eventLoop().execute() queues tasks run by runPendingTasks(),
 * which is exactly where the fix schedules its flush.
 */
public class MarketDataWebSocketInitOrderingTest {

    private ControllableStateManager stateManager;
    private MarketDataWebSocket ws;

    @Before
    public void setUp() {
        stateManager = new ControllableStateManager();
        ws = new MarketDataWebSocket();
        ws.setStateManager(stateManager);
    }

    @After
    public void tearDown() {
        ws.close();
    }

    // ==================== Helpers ====================

    private EmbeddedChannel newChannel() {
        // newHandlerForTest() lazily initializes the shared channel group without binding 8081.
        return new EmbeddedChannel(DefaultChannelId.newInstance(), ws.newHandlerForTest());
    }

    private void subscribe(EmbeddedChannel ch, int marketId) {
        ch.writeInbound(new TextWebSocketFrame("{\"action\":\"subscribe\",\"marketId\":" + marketId + "}"));
    }

    /** Drain every queued outbound frame's text, releasing frames as we go. */
    private List<String> drainOutbound(EmbeddedChannel ch) {
        List<String> out = new ArrayList<>();
        Object o;
        while ((o = ch.readOutbound()) != null) {
            if (o instanceof TextWebSocketFrame) {
                TextWebSocketFrame f = (TextWebSocketFrame) o;
                out.add(f.text());
                f.release();
            }
        }
        return out;
    }

    private static String tradesFrame(int marketId, String tag) {
        return "{\"type\":\"TRADES_BATCH\",\"marketId\":" + marketId + ",\"tag\":\"" + tag + "\"}";
    }

    private static String candleUpdateFrame(int marketId, String tag) {
        return "{\"type\":\"CANDLE_UPDATE\",\"marketId\":" + marketId + ",\"tag\":\"" + tag + "\"}";
    }

    private static int indexOfTag(List<String> frames, String tag) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).contains("\"tag\":\"" + tag + "\"")) return i;
        }
        return -1;
    }

    // ==================== Tests ====================

    /**
     * Live frames broadcast during the async window are held and delivered AFTER the initial
     * snapshot, in arrival order.
     */
    @Test
    public void liveFramesAreBufferedThenDeliveredAfterInitialSnapshot() {
        EmbeddedChannel ch = newChannel();
        subscribe(ch, 1);

        // Subscribe wrote SUBSCRIPTION_CONFIRMED + the (empty) book snapshot synchronously.
        List<String> preFrames = drainOutbound(ch);
        assertTrue("confirmation sent", preFrames.stream().anyMatch(s -> s.contains("SUBSCRIPTION_CONFIRMED")));
        assertEquals("no live frames yet", -1, indexOfTag(preFrames, "LIVE1"));

        // Live trades + a live candle arrive while the initial state is still in flight.
        ws.broadcastMarketData(tradesFrame(1, "LIVE1"));
        ws.broadcastMarketData(candleUpdateFrame(1, "LIVEC"));
        ws.broadcastMarketData(tradesFrame(1, "LIVE2"));

        // Nothing delivered yet: all three are buffered behind the pending initial state.
        assertTrue("live frames buffered, not written early", drainOutbound(ch).isEmpty());

        // Async initial state settles.
        stateManager.completeTrades(1, tradesFrame(1, "INIT_TRADES"));
        stateManager.completeCandles(1, "{\"type\":\"CANDLE_HISTORY\",\"marketId\":1,\"tag\":\"INIT_CANDLES\"}");
        ch.runPendingTasks(); // runs the flush task scheduled on the event loop

        List<String> frames = drainOutbound(ch);
        int initTrades = indexOfTag(frames, "INIT_TRADES");
        int initCandles = indexOfTag(frames, "INIT_CANDLES");
        int live1 = indexOfTag(frames, "LIVE1");
        int liveC = indexOfTag(frames, "LIVEC");
        int live2 = indexOfTag(frames, "LIVE2");

        assertTrue("initial trades snapshot delivered", initTrades >= 0);
        assertTrue("initial candle history delivered", initCandles >= 0);
        assertTrue("live1 delivered", live1 >= 0);
        assertTrue("live candle delivered", liveC >= 0);
        assertTrue("live2 delivered", live2 >= 0);

        // Initial snapshot precedes every buffered live frame...
        assertTrue("live1 after initial trades", initTrades < live1);
        assertTrue("live candle after initial candles", initCandles < liveC);
        // ...and buffered live frames keep their arrival order.
        assertTrue("live arrival order preserved (LIVE1 < LIVEC)", live1 < liveC);
        assertTrue("live arrival order preserved (LIVEC < LIVE2)", liveC < live2);

        ch.finish();
    }

    /**
     * If both async fetches fail, no snapshot is written (preserving pre-fix behavior) but the
     * buffered live frames are still flushed and the channel resumes normal delivery: it must
     * never wedge permanently buffering.
     */
    @Test
    public void asyncFailureStillFlushesAndUnblocksChannel() {
        EmbeddedChannel ch = newChannel();
        subscribe(ch, 1);
        drainOutbound(ch); // discard confirmation + book

        ws.broadcastMarketData(tradesFrame(1, "LIVE1"));
        assertTrue("buffered while initializing", drainOutbound(ch).isEmpty());

        // Both DB reads fail / time out.
        stateManager.failTrades(1, new RuntimeException("db down"));
        stateManager.failCandles(1, new RuntimeException("db down"));
        ch.runPendingTasks();

        List<String> flushed = drainOutbound(ch);
        assertTrue("buffered live frame flushed despite failure", indexOfTag(flushed, "LIVE1") >= 0);
        assertFalse("no initial snapshot written on failure",
            flushed.stream().anyMatch(s -> s.contains("INIT_TRADES") || s.contains("INIT_CANDLES")));

        // Channel is unblocked: a subsequent live frame is written through immediately.
        ws.broadcastMarketData(tradesFrame(1, "LIVE_AFTER"));
        List<String> after = drainOutbound(ch);
        assertTrue("post-flush live frame delivered directly (no longer buffering)",
            indexOfTag(after, "LIVE_AFTER") >= 0);

        ch.finish();
    }

    /**
     * Buffering is per-channel and per-market: a live frame for one market/channel is never
     * mixed into another channel's stream.
     */
    @Test
    public void otherMarketsAndChannelsAreUnaffected() {
        EmbeddedChannel chA = newChannel(); // market 1
        EmbeddedChannel chB = newChannel(); // market 2
        subscribe(chA, 1);
        subscribe(chB, 2);
        drainOutbound(chA);
        drainOutbound(chB);

        // Live frames for each market arrive while both channels are initializing.
        ws.broadcastMarketData(tradesFrame(1, "M1_LIVE"));
        ws.broadcastMarketData(tradesFrame(2, "M2_LIVE"));

        // Both are buffered on their own channel; nothing delivered yet.
        assertTrue(drainOutbound(chA).isEmpty());
        assertTrue(drainOutbound(chB).isEmpty());

        // Settle both channels' initial state.
        stateManager.completeTrades(1, tradesFrame(1, "M1_INIT"));
        stateManager.completeCandles(1, "{\"type\":\"CANDLE_HISTORY\",\"marketId\":1,\"tag\":\"M1_INITC\"}");
        stateManager.completeTrades(2, tradesFrame(2, "M2_INIT"));
        stateManager.completeCandles(2, "{\"type\":\"CANDLE_HISTORY\",\"marketId\":2,\"tag\":\"M2_INITC\"}");
        chA.runPendingTasks();
        chB.runPendingTasks();

        List<String> a = drainOutbound(chA);
        List<String> b = drainOutbound(chB);

        assertTrue("A got its own market-1 live frame", indexOfTag(a, "M1_LIVE") >= 0);
        assertEquals("A never saw market-2's live frame", -1, indexOfTag(a, "M2_LIVE"));
        assertTrue("B got its own market-2 live frame", indexOfTag(b, "M2_LIVE") >= 0);
        assertEquals("B never saw market-1's live frame", -1, indexOfTag(b, "M1_LIVE"));

        // And each channel's live frame still lands after its own snapshot.
        assertTrue(indexOfTag(a, "M1_INIT") < indexOfTag(a, "M1_LIVE"));
        assertTrue(indexOfTag(b, "M2_INIT") < indexOfTag(b, "M2_LIVE"));

        chA.finish();
        chB.finish();
    }

    /**
     * The buffer is bounded: past MAX_INIT_BUFFER_FRAMES the oldest buffered frame is dropped
     * (drop-oldest) so a stuck DB read cannot balloon the heap, and the surviving frames keep
     * arrival order.
     */
    @Test
    public void bufferIsBoundedDroppingOldest() {
        EmbeddedChannel ch = newChannel();
        subscribe(ch, 1);
        drainOutbound(ch);

        int cap = MarketDataWebSocket.MAX_INIT_BUFFER_FRAMES;
        int overflow = 50;
        int total = cap + overflow;
        for (int i = 0; i < total; i++) {
            ws.broadcastMarketData(tradesFrame(1, "IDX" + i));
        }
        assertTrue("all buffered, none written early", drainOutbound(ch).isEmpty());

        stateManager.completeTrades(1, tradesFrame(1, "INIT_TRADES"));
        stateManager.completeCandles(1, "{\"type\":\"CANDLE_HISTORY\",\"marketId\":1,\"tag\":\"INIT_CANDLES\"}");
        ch.runPendingTasks();

        List<String> frames = drainOutbound(ch);
        List<String> live = new ArrayList<>();
        for (String f : frames) {
            if (f.contains("\"tag\":\"IDX")) live.add(f);
        }
        assertEquals("buffer capped at MAX_INIT_BUFFER_FRAMES", cap, live.size());
        // Oldest `overflow` frames (IDX0..IDX49) were dropped; survivors start at IDX50.
        assertTrue("oldest dropped: first survivor is IDX" + overflow,
            live.get(0).contains("\"tag\":\"IDX" + overflow + "\""));
        assertTrue("newest retained is IDX" + (total - 1),
            live.get(live.size() - 1).contains("\"tag\":\"IDX" + (total - 1) + "\""));

        ch.finish();
    }

    // ==================== Test state manager ====================

    /**
     * A GatewayStateManager whose async initial-state fetches complete on command, keyed by
     * market so multiple channels can be driven independently. Order-book and ticker lookups
     * return null so the handler emits the (synchronous) empty-book snapshot and no ticker.
     */
    static final class ControllableStateManager extends GatewayStateManager {
        private final Map<Integer, CompletableFuture<String>> trades = new ConcurrentHashMap<>();
        private final Map<Integer, CompletableFuture<String>> candles = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<String> recentTradesJsonAsync(int limit, int marketId) {
            return trades.computeIfAbsent(marketId, k -> new CompletableFuture<>());
        }

        @Override
        public CompletableFuture<String> buildCandleHistoryJsonAsync(int marketId, String interval, int limit) {
            return candles.computeIfAbsent(marketId, k -> new CompletableFuture<>());
        }

        @Override
        public GatewayOrderBook getOrderBook(int marketId) {
            return null; // -> handler sends the synchronous empty-book snapshot
        }

        @Override
        public String getTickerStats(int marketId) {
            return null; // -> no ticker frame
        }

        void completeTrades(int marketId, String json) {
            recentTradesJsonAsync(50, marketId).complete(json);
        }

        void completeCandles(int marketId, String json) {
            buildCandleHistoryJsonAsync(marketId, "1m", 200).complete(json);
        }

        void failTrades(int marketId, Throwable t) {
            recentTradesJsonAsync(50, marketId).completeExceptionally(t);
        }

        void failCandles(int marketId, Throwable t) {
            buildCandleHistoryJsonAsync(marketId, "1m", 200).completeExceptionally(t);
        }
    }
}
