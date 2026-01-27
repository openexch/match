package com.match.domain;

import com.match.domain.enums.OrderEventType;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for domain enums: OrderSide, OrderType, OrderEventType.
 * ConnectionState is tested in the gateway module (different package).
 */
public class EnumTest {

    // ==================== OrderSide ====================

    @Test
    public void testOrderSide_BidExists() {
        assertEquals(OrderSide.BID, OrderSide.valueOf("BID"));
    }

    @Test
    public void testOrderSide_AskExists() {
        assertEquals(OrderSide.ASK, OrderSide.valueOf("ASK"));
    }

    @Test
    public void testOrderSide_ValuesCount() {
        OrderSide[] values = OrderSide.values();
        assertEquals(2, values.length);
    }

    @Test
    public void testOrderSide_ValuesContainBothSides() {
        OrderSide[] values = OrderSide.values();
        assertEquals(OrderSide.BID, values[0]);
        assertEquals(OrderSide.ASK, values[1]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOrderSide_InvalidValue_Throws() {
        OrderSide.valueOf("INVALID");
    }

    // ==================== OrderType ====================

    @Test
    public void testOrderType_LimitExists() {
        assertEquals(OrderType.LIMIT, OrderType.valueOf("LIMIT"));
    }

    @Test
    public void testOrderType_MarketExists() {
        assertEquals(OrderType.MARKET, OrderType.valueOf("MARKET"));
    }

    @Test
    public void testOrderType_LimitMakerExists() {
        assertEquals(OrderType.LIMIT_MAKER, OrderType.valueOf("LIMIT_MAKER"));
    }

    @Test
    public void testOrderType_ValuesCount() {
        OrderType[] values = OrderType.values();
        assertEquals(3, values.length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOrderType_InvalidValue_Throws() {
        OrderType.valueOf("STOP_LOSS");
    }

    // ==================== OrderEventType ====================

    @Test
    public void testOrderEventType_CreateExists() {
        assertEquals(OrderEventType.CREATE, OrderEventType.valueOf("CREATE"));
    }

    @Test
    public void testOrderEventType_CancelExists() {
        assertEquals(OrderEventType.CANCEL, OrderEventType.valueOf("CANCEL"));
    }

    @Test
    public void testOrderEventType_UpdateExists() {
        assertEquals(OrderEventType.UPDATE, OrderEventType.valueOf("UPDATE"));
    }

    @Test
    public void testOrderEventType_ValuesCount() {
        OrderEventType[] values = OrderEventType.values();
        assertEquals(3, values.length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOrderEventType_InvalidValue_Throws() {
        OrderEventType.valueOf("DELETE");
    }
}
