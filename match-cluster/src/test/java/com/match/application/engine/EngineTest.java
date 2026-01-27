package com.match.application.engine;

import com.match.application.orderbook.DirectMatchingEngine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.agrona.collections.Int2ObjectHashMap;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for Engine — main matching engine and MarketConfig.
 */
public class EngineTest {

    private Engine engine;

    @Before
    public void setUp() {
        engine = new Engine();
    }

    // ==================== Helpers ====================

    private CreateOrderCommand createLimitCmd(long userId, OrderSide side, double price, double quantity) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(quantity));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private CreateOrderCommand createMarketBuyCmd(long userId, double totalPrice) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(OrderSide.BID);
        cmd.setOrderType(OrderType.MARKET);
        cmd.setPrice(0);
        cmd.setQuantity(0);
        cmd.setTotalPrice(FixedPoint.fromDouble(totalPrice));
        return cmd;
    }

    private CreateOrderCommand createMarketSellCmd(long userId, double quantity) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(OrderSide.ASK);
        cmd.setOrderType(OrderType.MARKET);
        cmd.setPrice(0);
        cmd.setQuantity(FixedPoint.fromDouble(quantity));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private CreateOrderCommand createLimitMakerCmd(long userId, OrderSide side, double price, double quantity) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT_MAKER);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(quantity));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private CancelOrderCommand createCancelCmd(long userId, long orderId) {
        CancelOrderCommand cmd = new CancelOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderId(orderId);
        return cmd;
    }

    // ==================== Initialization ====================

    @Test
    public void testEngineInitialization_AllMarketsCreated() {
        for (MarketConfig config : MarketConfig.ALL_MARKETS) {
            assertNotNull("Engine missing for market " + config.symbol,
                engine.getEngine(config.marketId));
        }
    }

    @Test
    public void testEngineInitialization_AllFiveMarkets() {
        assertNotNull(engine.getEngine(Engine.MARKET_BTC_USD));
        assertNotNull(engine.getEngine(Engine.MARKET_ETH_USD));
        assertNotNull(engine.getEngine(Engine.MARKET_SOL_USD));
        assertNotNull(engine.getEngine(Engine.MARKET_XRP_USD));
        assertNotNull(engine.getEngine(Engine.MARKET_DOGE_USD));
    }

    @Test
    public void testGetEngine_UnknownMarket_ReturnsNull() {
        assertNull(engine.getEngine(999));
    }

    @Test
    public void testGetEngines_ReturnsAllEngines() {
        Int2ObjectHashMap<DirectMatchingEngine> engines = engine.getEngines();
        assertNotNull(engines);
        assertEquals(MarketConfig.ALL_MARKETS.length, engines.size());
    }

    // ==================== Limit Order — No Match ====================

    @Test
    public void testAcceptOrder_LimitBuyNoMatch_AddedToBook() {
        CreateOrderCommand cmd = createLimitCmd(100, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertFalse(btcEngine.isBidEmpty());
        assertTrue(btcEngine.isAskEmpty());
    }

    @Test
    public void testAcceptOrder_LimitSellNoMatch_AddedToBook() {
        CreateOrderCommand cmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertTrue(btcEngine.isBidEmpty());
        assertFalse(btcEngine.isAskEmpty());
    }

    // ==================== Limit Order — Full Match ====================

    @Test
    public void testAcceptOrder_LimitFullMatch() {
        // Place ask
        CreateOrderCommand askCmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, askCmd, System.nanoTime());

        // Place matching bid
        CreateOrderCommand bidCmd = createLimitCmd(101, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Both sides should be empty after full match
        assertTrue(btcEngine.isAskEmpty());
        assertTrue(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_LimitPartialMatch() {
        // Place ask with qty 5
        CreateOrderCommand askCmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 5.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, askCmd, System.nanoTime());

        // Place bid with qty 3 — partial match
        CreateOrderCommand bidCmd = createLimitCmd(101, OrderSide.BID, 100000.0, 3.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Ask should still have remaining, bid consumed
        assertFalse(btcEngine.isAskEmpty());
        assertTrue(btcEngine.isBidEmpty());
    }

    // ==================== Market Order ====================

    @Test
    public void testAcceptOrder_MarketBuy() {
        // Place ask at 100000
        CreateOrderCommand askCmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 2.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, askCmd, System.nanoTime());

        // Market buy with budget for 1 unit (100000)
        CreateOrderCommand marketCmd = createMarketBuyCmd(101, 100000.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, marketCmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Ask should still have 1 remaining
        assertFalse(btcEngine.isAskEmpty());
    }

    @Test
    public void testAcceptOrder_MarketSell() {
        // Place bid at 100000
        CreateOrderCommand bidCmd = createLimitCmd(100, OrderSide.BID, 100000.0, 5.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        // Market sell 3 units
        CreateOrderCommand marketCmd = createMarketSellCmd(101, 3.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, marketCmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Bid should still have 2 remaining
        assertFalse(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_MarketOrderOnEmptyBook() {
        // Market sell on empty book — should not throw
        CreateOrderCommand marketCmd = createMarketSellCmd(101, 5.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, marketCmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertTrue(btcEngine.isBidEmpty());
        assertTrue(btcEngine.isAskEmpty());
    }

    // ==================== Limit Maker ====================

    @Test
    public void testAcceptOrder_LimitMaker_PlacedWhenNoCross() {
        // Empty book — limit maker should be placed
        CreateOrderCommand cmd = createLimitMakerCmd(100, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertFalse(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_LimitMaker_RejectedWhenWouldCross() {
        // Place ask at 100000
        CreateOrderCommand askCmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, askCmd, System.nanoTime());

        // Limit maker buy at 100000 — would cross → rejected
        CreateOrderCommand makerCmd = createLimitMakerCmd(101, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, makerCmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Bid should still be empty (order rejected)
        assertTrue(btcEngine.isBidEmpty());
        // Ask should still have the original order
        assertFalse(btcEngine.isAskEmpty());
    }

    @Test
    public void testAcceptOrder_LimitMakerSell_PlacedWhenNoCross() {
        // Place bid at 99000
        CreateOrderCommand bidCmd = createLimitCmd(100, OrderSide.BID, 99000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        // Limit maker sell at 100000 — would not cross
        CreateOrderCommand makerCmd = createLimitMakerCmd(101, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, makerCmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertFalse(btcEngine.isAskEmpty());
        assertFalse(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_LimitMakerSell_RejectedWhenWouldCross() {
        // Place bid at 100000
        CreateOrderCommand bidCmd = createLimitCmd(100, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        // Limit maker sell at 100000 — would cross
        CreateOrderCommand makerCmd = createLimitMakerCmd(101, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, makerCmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertTrue(btcEngine.isAskEmpty()); // rejected
        assertFalse(btcEngine.isBidEmpty()); // original bid still there
    }

    // ==================== Cancel Order ====================

    @Test
    public void testAcceptOrder_Cancel_Successful() {
        // Place a limit buy — will get orderId 1
        CreateOrderCommand createCmd = createLimitCmd(100, OrderSide.BID, 100000.0, 1.0);
        long startId = engine.getOrderIdGenerator();
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, createCmd, System.nanoTime());

        DirectMatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertFalse(btcEngine.isBidEmpty());

        // Cancel using the generated order ID
        CancelOrderCommand cancelCmd = createCancelCmd(100, startId);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CANCEL, cancelCmd, System.nanoTime());

        assertTrue(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_Cancel_NonExistent() {
        // Cancel a non-existent order — should not throw
        CancelOrderCommand cancelCmd = createCancelCmd(100, 9999);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CANCEL, cancelCmd, System.nanoTime());
        // No exception = pass
    }

    // ==================== Unknown Market ====================

    @Test
    public void testAcceptOrder_UnknownMarket_SilentlyIgnored() {
        CreateOrderCommand cmd = createLimitCmd(100, OrderSide.BID, 100.0, 1.0);
        // Should not throw
        engine.acceptOrder(999, Engine.CMD_CREATE, cmd, System.nanoTime());
    }

    // ==================== Order ID Generation ====================

    @Test
    public void testOrderIdGenerator_Increments() {
        long id1 = engine.getOrderIdGenerator();
        CreateOrderCommand cmd1 = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd1, System.nanoTime());
        long id2 = engine.getOrderIdGenerator();
        assertEquals(id1 + 1, id2);

        CreateOrderCommand cmd2 = createLimitCmd(101, OrderSide.ASK, 100001.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd2, System.nanoTime());
        long id3 = engine.getOrderIdGenerator();
        assertEquals(id2 + 1, id3);
    }

    @Test
    public void testOrderIdGenerator_SetAndGet() {
        engine.setOrderIdGenerator(1000);
        assertEquals(1000, engine.getOrderIdGenerator());
    }

    @Test
    public void testOrderIdGenerator_SnapshotRestore() {
        // Simulate some orders
        CreateOrderCommand cmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd, System.nanoTime());

        long savedId = engine.getOrderIdGenerator();

        // Restore
        Engine newEngine = new Engine();
        newEngine.setOrderIdGenerator(savedId);
        assertEquals(savedId, newEngine.getOrderIdGenerator());
    }

    // ==================== Event Publisher ====================

    @Test
    public void testEventPublisher_NullByDefault() {
        assertNull(engine.getEventPublisher());
    }

    // ==================== MarketConfig ====================

    @Test
    public void testMarketConfig_GetById() {
        MarketConfig btc = MarketConfig.getById(1);
        assertNotNull(btc);
        assertEquals("BTC-USD", btc.symbol);
        assertEquals(1, btc.marketId);
    }

    @Test
    public void testMarketConfig_GetById_AllMarkets() {
        for (MarketConfig config : MarketConfig.ALL_MARKETS) {
            MarketConfig found = MarketConfig.getById(config.marketId);
            assertNotNull(found);
            assertEquals(config.symbol, found.symbol);
        }
    }

    @Test
    public void testMarketConfig_GetById_Unknown_ReturnsNull() {
        assertNull(MarketConfig.getById(999));
    }

    @Test
    public void testMarketConfig_GetBySymbol() {
        MarketConfig eth = MarketConfig.getBySymbol("ETH-USD");
        assertNotNull(eth);
        assertEquals(2, eth.marketId);
    }

    @Test
    public void testMarketConfig_GetBySymbol_AllMarkets() {
        String[] symbols = { "BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD" };
        for (String symbol : symbols) {
            assertNotNull("Missing config for " + symbol, MarketConfig.getBySymbol(symbol));
        }
    }

    @Test
    public void testMarketConfig_GetBySymbol_Unknown_ReturnsNull() {
        assertNull(MarketConfig.getBySymbol("UNKNOWN-USD"));
    }

    @Test
    public void testMarketConfig_GetPriceLevels() {
        MarketConfig btc = MarketConfig.BTC_USD;
        int levels = btc.getPriceLevels();
        assertTrue("BTC should have significant price levels", levels > 1000);
    }

    @Test
    public void testMarketConfig_PriceFields() {
        MarketConfig btc = MarketConfig.BTC_USD;
        assertEquals(FixedPoint.fromDouble(50_000.0), btc.basePrice);
        assertEquals(FixedPoint.fromDouble(150_000.0), btc.maxPrice);
        assertEquals(FixedPoint.fromDouble(1.0), btc.tickSize);
    }

    @Test
    public void testMarketConfig_AllMarketsArray() {
        assertEquals(5, MarketConfig.ALL_MARKETS.length);
    }

    // ==================== Close ====================

    @Test
    public void testClose_NoException() {
        engine.close(); // should not throw
    }
}
