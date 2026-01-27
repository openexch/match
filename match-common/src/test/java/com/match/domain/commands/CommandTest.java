package com.match.domain.commands;

import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for command classes — CreateOrderCommand, CancelOrderCommand, UpdateOrderCommand.
 * Covers all getters/setters and reset().
 */
public class CommandTest {

    // ==================== CreateOrderCommand ====================

    @Test
    public void createOrderCommand_allGettersSetters() {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(42L);
        cmd.setPrice(10000L);
        cmd.setQuantity(500L);
        cmd.setTotalPrice(5000000L);
        cmd.setOrderSide(OrderSide.BID);
        cmd.setOrderType(OrderType.LIMIT);

        assertEquals(42L, cmd.getUserId());
        assertEquals(10000L, cmd.getPrice());
        assertEquals(500L, cmd.getQuantity());
        assertEquals(5000000L, cmd.getTotalPrice());
        assertEquals(OrderSide.BID, cmd.getOrderSide());
        assertEquals(OrderType.LIMIT, cmd.getOrderType());
    }

    @Test
    public void createOrderCommand_askSideMarketType() {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setOrderSide(OrderSide.ASK);
        cmd.setOrderType(OrderType.MARKET);

        assertEquals(OrderSide.ASK, cmd.getOrderSide());
        assertEquals(OrderType.MARKET, cmd.getOrderType());
    }

    @Test
    public void createOrderCommand_limitMakerType() {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setOrderType(OrderType.LIMIT_MAKER);
        assertEquals(OrderType.LIMIT_MAKER, cmd.getOrderType());
    }

    @Test
    public void createOrderCommand_resetClearsAll() {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(99L);
        cmd.setPrice(50000L);
        cmd.setQuantity(100L);
        cmd.setTotalPrice(5000000L);
        cmd.setOrderSide(OrderSide.ASK);
        cmd.setOrderType(OrderType.MARKET);

        cmd.reset();

        assertEquals(0L, cmd.getUserId());
        assertEquals(0L, cmd.getPrice());
        assertEquals(0L, cmd.getQuantity());
        assertEquals(0L, cmd.getTotalPrice());
        assertNull(cmd.getOrderSide());
        assertNull(cmd.getOrderType());
    }

    @Test
    public void createOrderCommand_resetThenReuse() {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(1L);
        cmd.setPrice(100L);
        cmd.reset();

        cmd.setUserId(2L);
        cmd.setPrice(200L);
        assertEquals(2L, cmd.getUserId());
        assertEquals(200L, cmd.getPrice());
    }

    // ==================== CancelOrderCommand ====================

    @Test
    public void cancelOrderCommand_allGettersSetters() {
        CancelOrderCommand cmd = new CancelOrderCommand();
        cmd.setUserId(77L);
        cmd.setOrderId(12345L);

        assertEquals(77L, cmd.getUserId());
        assertEquals(12345L, cmd.getOrderId());
    }

    @Test
    public void cancelOrderCommand_resetClearsAll() {
        CancelOrderCommand cmd = new CancelOrderCommand();
        cmd.setUserId(88L);
        cmd.setOrderId(99999L);

        cmd.reset();

        assertEquals(0L, cmd.getUserId());
        assertEquals(0L, cmd.getOrderId());
    }

    @Test
    public void cancelOrderCommand_resetThenReuse() {
        CancelOrderCommand cmd = new CancelOrderCommand();
        cmd.setUserId(1L);
        cmd.setOrderId(1L);
        cmd.reset();

        cmd.setUserId(2L);
        cmd.setOrderId(2L);
        assertEquals(2L, cmd.getUserId());
        assertEquals(2L, cmd.getOrderId());
    }

    // ==================== UpdateOrderCommand ====================

    @Test
    public void updateOrderCommand_allGettersSetters() {
        UpdateOrderCommand cmd = new UpdateOrderCommand();
        cmd.setUserId(55L);
        cmd.setOrderId(67890L);
        cmd.setPrice(30000L);
        cmd.setQuantity(750L);
        cmd.setOrderSide(OrderSide.ASK);
        cmd.setOrderType(OrderType.LIMIT);

        assertEquals(55L, cmd.getUserId());
        assertEquals(67890L, cmd.getOrderId());
        assertEquals(30000L, cmd.getPrice());
        assertEquals(750L, cmd.getQuantity());
        assertEquals(OrderSide.ASK, cmd.getOrderSide());
        assertEquals(OrderType.LIMIT, cmd.getOrderType());
    }

    @Test
    public void updateOrderCommand_bidSideLimitMaker() {
        UpdateOrderCommand cmd = new UpdateOrderCommand();
        cmd.setOrderSide(OrderSide.BID);
        cmd.setOrderType(OrderType.LIMIT_MAKER);

        assertEquals(OrderSide.BID, cmd.getOrderSide());
        assertEquals(OrderType.LIMIT_MAKER, cmd.getOrderType());
    }

    @Test
    public void updateOrderCommand_resetClearsAll() {
        UpdateOrderCommand cmd = new UpdateOrderCommand();
        cmd.setUserId(11L);
        cmd.setOrderId(22L);
        cmd.setPrice(33L);
        cmd.setQuantity(44L);
        cmd.setOrderSide(OrderSide.BID);
        cmd.setOrderType(OrderType.MARKET);

        cmd.reset();

        assertEquals(0L, cmd.getUserId());
        assertEquals(0L, cmd.getOrderId());
        assertEquals(0L, cmd.getPrice());
        assertEquals(0L, cmd.getQuantity());
        assertNull(cmd.getOrderSide());
        assertNull(cmd.getOrderType());
    }

    @Test
    public void updateOrderCommand_resetThenReuse() {
        UpdateOrderCommand cmd = new UpdateOrderCommand();
        cmd.setUserId(1L);
        cmd.setOrderId(1L);
        cmd.setPrice(1L);
        cmd.setQuantity(1L);
        cmd.reset();

        cmd.setUserId(5L);
        cmd.setOrderId(6L);
        cmd.setPrice(7L);
        cmd.setQuantity(8L);
        assertEquals(5L, cmd.getUserId());
        assertEquals(6L, cmd.getOrderId());
        assertEquals(7L, cmd.getPrice());
        assertEquals(8L, cmd.getQuantity());
    }
}
