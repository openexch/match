package com.match.application.orderbook;

import com.match.domain.Level;
import com.match.domain.Order;
import com.match.domain.enums.OrderType;
import com.match.domain.interfaces.OrderBookSide;

import java.math.BigDecimal;
import java.util.*;

public class OrderBookSideImpl implements OrderBookSide {

    private final Map<BigDecimal, Level> priceLevels;
    private final NavigableSet<BigDecimal> prices;
    private final Map<OrderType, OrderTypeSideHandler> handlers;
    private final Comparator<BigDecimal> priceComparator;

    public OrderBookSideImpl(Comparator<BigDecimal> priceComparator) {
        this.priceComparator = priceComparator;
        this.priceLevels = new HashMap<>();
        this.prices = new TreeSet<>(priceComparator);
        this.handlers = new HashMap<>();
    }

    @Override
    public void placeOrder(Order order) {
        Level level = priceLevels.get(order.getPrice());
        if (level != null) {
            level.append(order);
        } else {
            createPriceLevel(order);
        }
    }

    @Override
    public void createPriceLevel(Order order) {
        Level level = new Level(order);
        priceLevels.put(order.getPrice(), level);
        prices.add(order.getPrice());
    }

    @Override
    public void removePriceLevel(BigDecimal price) {
        priceLevels.remove(price);
        prices.remove(price);
    }

    @Override
    public void cancelOrder(Order order) {
        removeOrder(order);
    }

    @Override
    public void removeOrder(Order order) {
        Level level = priceLevels.get(order.getPrice());
        if (level != null) {
            level.delete(order.getId());
            if (level.getLength() == 0) {
                removePriceLevel(order.getPrice());
            }
        }
    }

    @Override
    public BigDecimal getBestPrice() {
        if (!prices.isEmpty()) {
            return prices.first();
        }
        return null;
    }

    @Override
    public Collection<BigDecimal> getPrices() {
        return prices;
    }

    @Override
    public Map<BigDecimal, Level> getPriceLevels() {
        return priceLevels;
    }

    @Override
    public void registerHandler(OrderType orderType, OrderTypeSideHandler handler) {
        handlers.put(orderType, handler);
    }

    @Override
    public OrderTypeSideHandler getHandler(OrderType orderType) throws Exception {
        OrderTypeSideHandler handler = handlers.get(orderType);
        if (handler != null) {
            return handler;
        }
        throw new Exception("Handler not found for order type: " + orderType);
    }

    @Override
    public boolean canMatch(Order taker) {
        if (taker.getType() == OrderType.MARKET && !prices.isEmpty()) {
            return true;
        }
        BigDecimal bestPrice = getBestPrice();
        if (bestPrice == null) {
            return false;
        }
        // For ask side, taker price should be >= best price.
        // For bid side, taker price should be <= best price.
        return priceComparator.compare(taker.getPrice(), bestPrice) >= 0;
    }
} 