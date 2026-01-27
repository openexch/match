package com.match.domain;

import com.match.domain.enums.OrderEventType;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Comprehensive tests for domain model classes: Order, Level, OrderMatch, OrderEvent.
 */
public class DomainModelTest {

    private Order order;

    @Before
    public void setUp() {
        order = new Order();
    }

    // ==================== Order: Getters/Setters ====================

    @Test
    public void testOrder_SetAndGetId() {
        order.setId(42L);
        assertEquals(42L, order.getId());
    }

    @Test
    public void testOrder_SetAndGetUserId() {
        order.setUserId(999L);
        assertEquals(999L, order.getUserId());
    }

    @Test
    public void testOrder_SetAndGetPrice() {
        long price = FixedPoint.fromDouble(50000.0);
        order.setPrice(price);
        assertEquals(price, order.getPrice());
    }

    @Test
    public void testOrder_SetAndGetQuantity() {
        long qty = FixedPoint.fromDouble(1.5);
        order.setQuantity(qty);
        assertEquals(qty, order.getQuantity());
    }

    @Test
    public void testOrder_SetAndGetTotalPrice() {
        long tp = FixedPoint.fromDouble(75000.0);
        order.setTotalPrice(tp);
        assertEquals(tp, order.getTotalPrice());
    }

    @Test
    public void testOrder_SetAndGetRemainingQuantity() {
        long rq = FixedPoint.fromDouble(0.75);
        order.setRemainingQuantity(rq);
        assertEquals(rq, order.getRemainingQuantity());
    }

    @Test
    public void testOrder_SetAndGetSide() {
        order.setSide(OrderSide.BID);
        assertEquals(OrderSide.BID, order.getSide());

        order.setSide(OrderSide.ASK);
        assertEquals(OrderSide.ASK, order.getSide());
    }

    @Test
    public void testOrder_SetAndGetMarketId() {
        order.setMarketId(3);
        assertEquals(3, order.getMarketId());
    }

    @Test
    public void testOrder_SetAndGetType() {
        order.setType(OrderType.LIMIT);
        assertEquals(OrderType.LIMIT, order.getType());

        order.setType(OrderType.MARKET);
        assertEquals(OrderType.MARKET, order.getType());

        order.setType(OrderType.LIMIT_MAKER);
        assertEquals(OrderType.LIMIT_MAKER, order.getType());
    }

    @Test
    public void testOrder_SetAndGetAcceptedAtNanos() {
        long nanos = System.nanoTime();
        order.setAcceptedAtNanos(nanos);
        assertEquals(nanos, order.getAcceptedAtNanos());
    }

    @Test
    public void testOrder_SetAndGetProcessedAtNanos() {
        long nanos = System.nanoTime();
        order.setProcessedAtNanos(nanos);
        assertEquals(nanos, order.getProcessedAtNanos());
    }

    // ==================== Order: reset() ====================

    @Test
    public void testOrder_Reset_ClearsAllFields() {
        order.setId(1L);
        order.setUserId(2L);
        order.setPrice(FixedPoint.fromDouble(100.0));
        order.setQuantity(FixedPoint.fromDouble(10.0));
        order.setTotalPrice(FixedPoint.fromDouble(1000.0));
        order.setRemainingQuantity(FixedPoint.fromDouble(5.0));
        order.setSide(OrderSide.ASK);
        order.setMarketId(1);
        order.setType(OrderType.LIMIT);
        order.setAcceptedAtNanos(123456789L);
        order.setProcessedAtNanos(987654321L);

        order.reset();

        assertEquals(0L, order.getId());
        assertEquals(0L, order.getUserId());
        assertEquals(0L, order.getPrice());
        assertEquals(0L, order.getQuantity());
        assertEquals(0L, order.getTotalPrice());
        assertEquals(0L, order.getRemainingQuantity());
        assertNull(order.getSide());
        assertEquals(0, order.getMarketId());
        assertNull(order.getType());
        assertEquals(0L, order.getAcceptedAtNanos());
        assertEquals(0L, order.getProcessedAtNanos());
    }

    // ==================== Order: Convenience (FixedPoint) ====================

    @Test
    public void testOrder_GetPriceAsDouble() {
        order.setPrice(FixedPoint.fromDouble(50000.12345678));
        assertEquals(50000.12345678, order.getPriceAsDouble(), 0.000000005);
    }

    @Test
    public void testOrder_SetPriceFromDouble() {
        order.setPriceFromDouble(99.99);
        assertEquals(99.99, order.getPriceAsDouble(), 0.000000005);
        assertEquals(FixedPoint.fromDouble(99.99), order.getPrice());
    }

    @Test
    public void testOrder_GetQuantityAsDouble() {
        order.setQuantity(FixedPoint.fromDouble(3.5));
        assertEquals(3.5, order.getQuantityAsDouble(), 0.000000005);
    }

    @Test
    public void testOrder_SetQuantityFromDouble() {
        order.setQuantityFromDouble(7.25);
        assertEquals(7.25, order.getQuantityAsDouble(), 0.000000005);
        assertEquals(FixedPoint.fromDouble(7.25), order.getQuantity());
    }

    @Test
    public void testOrder_GetRemainingQuantityAsDouble() {
        order.setRemainingQuantity(FixedPoint.fromDouble(0.001));
        assertEquals(0.001, order.getRemainingQuantityAsDouble(), 0.000000005);
    }

    // ==================== Order: isFilled() ====================

    @Test
    public void testOrder_IsFilled_WhenRemainingZero() {
        order.setRemainingQuantity(0L);
        assertTrue(order.isFilled());
    }

    @Test
    public void testOrder_IsFilled_WhenRemainingNegative() {
        order.setRemainingQuantity(-1L);
        assertTrue(order.isFilled());
    }

    @Test
    public void testOrder_IsFilled_WhenRemainingPositive() {
        order.setRemainingQuantity(FixedPoint.fromDouble(0.5));
        assertFalse(order.isFilled());
    }

    @Test
    public void testOrder_IsFilled_DefaultIsZero_SoFilled() {
        // Default remainingQuantity is 0, so isFilled() should be true
        assertTrue(new Order().isFilled());
    }

    // ==================== Level: Constructor(long price) ====================

    @Test
    public void testLevel_ConstructorWithPrice() {
        long price = FixedPoint.fromDouble(100.0);
        Level level = new Level(price);

        assertEquals(price, level.getPrice());
        assertTrue(level.isEmpty());
        assertEquals(0, level.getLength());
        assertEquals(0, level.getOrderCount());
        assertEquals(0L, level.getTotalQuantity());
        assertNotNull(level.getOrders());
        assertTrue(level.getOrders().isEmpty());
    }

    // ==================== Level: Constructor(Order) ====================

    @Test
    public void testLevel_ConstructorWithOrder() {
        Order o = makeOrder(1L, FixedPoint.fromDouble(200.0), FixedPoint.fromDouble(5.0));
        Level level = new Level(o);

        assertEquals(o.getPrice(), level.getPrice());
        assertFalse(level.isEmpty());
        assertEquals(1, level.getLength());
        assertEquals(1, level.getOrderCount());
        assertEquals(o.getRemainingQuantity(), level.getTotalQuantity());
    }

    // ==================== Level: append() ====================

    @Test
    public void testLevel_Append_SingleOrder() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(3.0));

        level.append(o);

        assertEquals(1, level.getLength());
        assertEquals(o.getRemainingQuantity(), level.getTotalQuantity());
        assertSame(o, level.getOrder(0));
    }

    @Test
    public void testLevel_Append_MultipleOrders_UpdatesTotalQuantity() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o1 = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(2.0));
        Order o2 = makeOrder(2L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(3.0));
        Order o3 = makeOrder(3L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(5.0));

        level.append(o1);
        level.append(o2);
        level.append(o3);

        assertEquals(3, level.getLength());
        long expected = FixedPoint.fromDouble(2.0) + FixedPoint.fromDouble(3.0) + FixedPoint.fromDouble(5.0);
        assertEquals(expected, level.getTotalQuantity());
    }

    // ==================== Level: delete() ====================

    @Test
    public void testLevel_Delete_LastElement() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(5.0));
        level.append(o);

        level.delete(1L);

        assertTrue(level.isEmpty());
        assertEquals(0L, level.getTotalQuantity());
    }

    @Test
    public void testLevel_Delete_MiddleElement_SwapsWithLast() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o1 = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(2.0));
        Order o2 = makeOrder(2L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(3.0));
        Order o3 = makeOrder(3L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(5.0));
        level.append(o1);
        level.append(o2);
        level.append(o3);

        level.delete(1L); // Delete first — last (o3) should swap into position 0

        assertEquals(2, level.getLength());
        // o3 swapped to index 0, o2 stays at index 1
        assertSame(o3, level.getOrder(0));
        assertSame(o2, level.getOrder(1));
        // Total quantity = o2 + o3
        long expected = FixedPoint.fromDouble(3.0) + FixedPoint.fromDouble(5.0);
        assertEquals(expected, level.getTotalQuantity());
    }

    @Test
    public void testLevel_Delete_NonExistentId_NoEffect() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(5.0));
        level.append(o);

        level.delete(999L);

        assertEquals(1, level.getLength());
        assertEquals(o.getRemainingQuantity(), level.getTotalQuantity());
    }

    @Test
    public void testLevel_Delete_LastInList_NoSwapNeeded() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o1 = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(2.0));
        Order o2 = makeOrder(2L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(3.0));
        level.append(o1);
        level.append(o2);

        level.delete(2L); // Delete last element — no swap needed

        assertEquals(1, level.getLength());
        assertSame(o1, level.getOrder(0));
        assertEquals(FixedPoint.fromDouble(2.0), level.getTotalQuantity());
    }

    // ==================== Level: updateQuantity() ====================

    @Test
    public void testLevel_UpdateQuantity_PositiveDelta() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        level.append(o);

        long delta = FixedPoint.fromDouble(5.0);
        level.updateQuantity(delta);

        assertEquals(FixedPoint.fromDouble(10.0) + delta, level.getTotalQuantity());
    }

    @Test
    public void testLevel_UpdateQuantity_NegativeDelta() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        level.append(o);

        long delta = -FixedPoint.fromDouble(3.0);
        level.updateQuantity(delta);

        assertEquals(FixedPoint.fromDouble(10.0) + delta, level.getTotalQuantity());
    }

    // ==================== Level: getHead/getTail ====================

    @Test
    public void testLevel_GetHead_EmptyLevel_ReturnsNull() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        assertNull(level.getHead());
    }

    @Test
    public void testLevel_GetTail_EmptyLevel_ReturnsNull() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        assertNull(level.getTail());
    }

    @Test
    public void testLevel_GetHead_ReturnsFirstOrder() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o1 = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(2.0));
        Order o2 = makeOrder(2L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(3.0));
        level.append(o1);
        level.append(o2);

        assertSame(o1, level.getHead());
    }

    @Test
    public void testLevel_GetTail_ReturnsLastOrder() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o1 = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(2.0));
        Order o2 = makeOrder(2L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(3.0));
        level.append(o1);
        level.append(o2);

        assertSame(o2, level.getTail());
    }

    @Test
    public void testLevel_GetHead_SingleOrder_SameAsTail() {
        Level level = new Level(FixedPoint.fromDouble(100.0));
        Order o = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(5.0));
        level.append(o);

        assertSame(level.getHead(), level.getTail());
    }

    // ==================== OrderMatch: Default Constructor ====================

    @Test
    public void testOrderMatch_DefaultConstructor() {
        OrderMatch match = new OrderMatch();
        assertNull(match.getTaker());
        assertNull(match.getMaker());
        assertEquals(0L, match.getPrice());
        assertEquals(0L, match.getQuantity());
    }

    // ==================== OrderMatch: Parameterized Constructor ====================

    @Test
    public void testOrderMatch_ParameterizedConstructor() {
        Order taker = makeOrder(1L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        Order maker = makeOrder(2L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(5.0));
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(5.0);

        OrderMatch match = new OrderMatch(taker, maker, price, qty);

        assertSame(taker, match.getTaker());
        assertSame(maker, match.getMaker());
        assertEquals(price, match.getPrice());
        assertEquals(qty, match.getQuantity());
    }

    // ==================== OrderMatch: Getters/Setters ====================

    @Test
    public void testOrderMatch_SetAndGetTaker() {
        OrderMatch match = new OrderMatch();
        Order taker = new Order();
        taker.setId(10L);
        match.setTaker(taker);
        assertSame(taker, match.getTaker());
    }

    @Test
    public void testOrderMatch_SetAndGetMaker() {
        OrderMatch match = new OrderMatch();
        Order maker = new Order();
        maker.setId(20L);
        match.setMaker(maker);
        assertSame(maker, match.getMaker());
    }

    @Test
    public void testOrderMatch_SetAndGetPrice() {
        OrderMatch match = new OrderMatch();
        long price = FixedPoint.fromDouble(250.0);
        match.setPrice(price);
        assertEquals(price, match.getPrice());
    }

    @Test
    public void testOrderMatch_SetAndGetQuantity() {
        OrderMatch match = new OrderMatch();
        long qty = FixedPoint.fromDouble(7.5);
        match.setQuantity(qty);
        assertEquals(qty, match.getQuantity());
    }

    // ==================== OrderMatch: reset() ====================

    @Test
    public void testOrderMatch_Reset_ClearsAllFields() {
        Order taker = new Order();
        Order maker = new Order();
        OrderMatch match = new OrderMatch(taker, maker, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(5.0));

        match.reset();

        assertNull(match.getTaker());
        assertNull(match.getMaker());
        assertEquals(0L, match.getPrice());
        assertEquals(0L, match.getQuantity());
    }

    // ==================== OrderMatch: set() ====================

    @Test
    public void testOrderMatch_Set_AllAtOnce() {
        OrderMatch match = new OrderMatch();
        Order taker = new Order();
        Order maker = new Order();
        long price = FixedPoint.fromDouble(300.0);
        long qty = FixedPoint.fromDouble(2.5);

        match.set(taker, maker, price, qty);

        assertSame(taker, match.getTaker());
        assertSame(maker, match.getMaker());
        assertEquals(price, match.getPrice());
        assertEquals(qty, match.getQuantity());
    }

    // ==================== OrderMatch: Convenience Methods ====================

    @Test
    public void testOrderMatch_GetPriceAsDouble() {
        OrderMatch match = new OrderMatch();
        match.setPrice(FixedPoint.fromDouble(42.12345678));
        assertEquals(42.12345678, match.getPriceAsDouble(), 0.000000005);
    }

    @Test
    public void testOrderMatch_GetQuantityAsDouble() {
        OrderMatch match = new OrderMatch();
        match.setQuantity(FixedPoint.fromDouble(0.001));
        assertEquals(0.001, match.getQuantityAsDouble(), 0.000000005);
    }

    @Test
    public void testOrderMatch_GetValue_PriceTimesQuantity() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(2.5);
        OrderMatch match = new OrderMatch(null, null, price, qty);

        long expectedValue = FixedPoint.multiply(price, qty);
        assertEquals(expectedValue, match.getValue());
        // 100.0 * 2.5 = 250.0
        assertEquals(250.0, FixedPoint.toDouble(match.getValue()), 0.0001);
    }

    @Test
    public void testOrderMatch_GetValue_ZeroQuantity() {
        OrderMatch match = new OrderMatch();
        match.setPrice(FixedPoint.fromDouble(100.0));
        match.setQuantity(0L);
        assertEquals(0L, match.getValue());
    }

    // ==================== OrderEvent ====================

    @Test
    public void testOrderEvent_Constructor_AndGetters() {
        Order o = new Order();
        o.setId(42L);
        OrderEvent event = new OrderEvent(o, OrderEventType.CREATE);

        assertSame(o, event.getOrder());
        assertEquals(OrderEventType.CREATE, event.getType());
    }

    @Test
    public void testOrderEvent_CancelType() {
        Order o = new Order();
        OrderEvent event = new OrderEvent(o, OrderEventType.CANCEL);
        assertEquals(OrderEventType.CANCEL, event.getType());
    }

    @Test
    public void testOrderEvent_UpdateType() {
        Order o = new Order();
        OrderEvent event = new OrderEvent(o, OrderEventType.UPDATE);
        assertEquals(OrderEventType.UPDATE, event.getType());
    }

    // ==================== Helpers ====================

    private Order makeOrder(long id, long price, long remainingQuantity) {
        Order o = new Order();
        o.setId(id);
        o.setPrice(price);
        o.setRemainingQuantity(remainingQuantity);
        return o;
    }
}
