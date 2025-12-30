package com.match.application.orderbook;

import com.match.domain.Order;
import com.match.domain.OrderMatch;
import com.match.domain.enums.OrderSide;
import com.match.domain.interfaces.OrderBook;
import com.match.domain.interfaces.OrderBookSide;
import com.match.infrastructure.Logger;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.EnumMap;

/**
 * Order book implementation optimized for ultra-low latency.
 * Uses primitive collections for O(1) lookups.
 */
public class OrderBookImpl implements OrderBook {
    private static final Logger logger = Logger.getLogger(OrderBookImpl.class);

    // Use EnumMap for side lookup (faster than HashMap)
    private final EnumMap<OrderSide, OrderBookSide> orderSides;

    // Use primitive long key map for order index
    private final Long2ObjectHashMap<Order> index;

    public OrderBookImpl(String id, String currency, String payment) {
        this.orderSides = new EnumMap<>(OrderSide.class);
        this.index = new Long2ObjectHashMap<>();
        logger.info("Order book created for %s%s pair", currency, payment);
    }

    @Override
    public void createOrder(Order order) throws Exception {
        matchOrder(order);
    }

    @Override
    public void cancelOrder(long orderId) throws Exception {
        Order existingOrder = index.get(orderId);
        if (existingOrder != null) {
            OrderBookSide side = orderSides.get(existingOrder.getSide());
            if (side != null) {
                side.removeOrder(existingOrder);
                index.remove(existingOrder.getId());
            }
        }
    }

    @Override
    public void updateOrder(long orderId) throws Exception {
        Order orderToUpdate = index.get(orderId);
        if (orderToUpdate != null) {
            OrderBookSide side = orderSides.get(orderToUpdate.getSide());
            if (side != null) {
                side.removeOrder(orderToUpdate);
                index.remove(orderToUpdate.getId());
            }
        }
    }

    private void matchOrder(Order order) throws Exception {
        OrderSide counterSide = (order.getSide() == OrderSide.ASK) ? OrderSide.BID : OrderSide.ASK;
        OrderBookSide makerSide = orderSides.get(counterSide);
        OrderBookSide takerSide = orderSides.get(order.getSide());

        if (makerSide.canMatch(order)) {
            OrderBookSide.OrderTypeSideHandler handler = makerSide.getHandler(order.getType());
            OrderBookSide.MatchResult result = handler.handle(makerSide, order);

            // Process matches
            for (OrderMatch match : result.getMatches()) {
                Order maker = match.getMaker();
                long newRemaining = maker.getRemainingQuantity() - match.getQuantity();
                maker.setRemainingQuantity(newRemaining);

                if (newRemaining <= 0L) {
                    makerSide.removeOrder(maker);
                    index.remove(maker.getId());
                }
            }

            // Place remaining order if not fully filled
            if (result.shouldPlaceOrder()) {
                Order placeOrder = result.getPlaceOrder();
                takerSide.placeOrder(placeOrder);
                index.put(placeOrder.getId(), placeOrder);
            }
        } else {
            // No match possible, place on book
            takerSide.placeOrder(order);
            index.put(order.getId(), order);
        }
    }

    @Override
    public void registerSide(OrderSide side, OrderBookSide sideHandler) {
        this.orderSides.put(side, sideHandler);
    }

    @Override
    public OrderBookSide getBidSide() {
        return orderSides.get(OrderSide.BID);
    }

    @Override
    public OrderBookSide getAskSide() {
        return orderSides.get(OrderSide.ASK);
    }

    /**
     * Get order by ID
     */
    public Order getOrder(long orderId) {
        return index.get(orderId);
    }

    /**
     * Get total number of orders in book
     */
    public int getOrderCount() {
        return index.size();
    }
}
