package com.match.domain;

import com.match.domain.interfaces.OrderBookSide.MatchResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for OrderBookSide.MatchResult data class.
 */
public class MatchResultTest {

    private Order createOrder() {
        Order order = new Order();
        order.setId(1L);
        order.setPrice(100L);
        order.setQuantity(50L);
        order.setRemainingQuantity(50L);
        return order;
    }

    @Test
    public void testConstructor_WithMatchesAndOrder() {
        Order order = createOrder();
        OrderMatch match = new OrderMatch(order, order, 50L, 100L);
        List<OrderMatch> matches = new ArrayList<>();
        matches.add(match);

        MatchResult result = new MatchResult(matches, order);
        assertNotNull(result.getMatches());
        assertEquals(1, result.getMatches().size());
        assertNotNull(result.getPlaceOrder());
    }

    @Test
    public void testHasMatches_True() {
        Order order = createOrder();
        OrderMatch match = new OrderMatch(order, order, 50L, 100L);
        List<OrderMatch> matches = new ArrayList<>();
        matches.add(match);

        MatchResult result = new MatchResult(matches, null);
        assertTrue(result.hasMatches());
    }

    @Test
    public void testHasMatches_EmptyList() {
        MatchResult result = new MatchResult(Collections.emptyList(), null);
        assertFalse(result.hasMatches());
    }

    @Test
    public void testHasMatches_NullList() {
        MatchResult result = new MatchResult(null, null);
        assertFalse(result.hasMatches());
    }

    @Test
    public void testShouldPlaceOrder_True() {
        Order order = createOrder();
        MatchResult result = new MatchResult(null, order);
        assertTrue(result.shouldPlaceOrder());
    }

    @Test
    public void testShouldPlaceOrder_Null() {
        MatchResult result = new MatchResult(null, null);
        assertFalse(result.shouldPlaceOrder());
    }

    @Test
    public void testGetPlaceOrder_ReturnsCorrectOrder() {
        Order order = createOrder();
        MatchResult result = new MatchResult(null, order);
        assertSame(order, result.getPlaceOrder());
    }

    @Test
    public void testGetMatches_ReturnsSameList() {
        List<OrderMatch> matches = new ArrayList<>();
        MatchResult result = new MatchResult(matches, null);
        assertSame(matches, result.getMatches());
    }
}
