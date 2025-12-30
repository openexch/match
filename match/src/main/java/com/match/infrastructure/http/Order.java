package com.match.infrastructure.http;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.OrderSide;
import com.match.infrastructure.generated.OrderType;

/**
 * Order received from HTTP JSON request.
 * Provides conversion methods for SBE encoding.
 */
public class Order {
    // JSON fields
    String userId;
    String market;
    String orderType;
    String orderSide;
    double price;
    double quantity;
    double totalPrice;
    long timestamp;

    // Market ID mapping
    private static final int MARKET_BTC_USD = 1;

    public Order() {
    }

    @Override
    public String toString() {
        return "Order{" +
                "userId='" + userId + '\'' +
                ", market='" + market + '\'' +
                ", orderType='" + orderType + '\'' +
                ", orderSide='" + orderSide + '\'' +
                ", price=" + price +
                ", quantity=" + quantity +
                ", totalPrice=" + totalPrice +
                ", timestamp=" + timestamp +
                '}';
    }

    /**
     * Convert userId string to long (using hash for non-numeric IDs)
     */
    public long getUserIdAsLong() {
        if (userId == null || userId.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            // Use hash for non-numeric user IDs
            return userId.hashCode() & 0x7FFFFFFFFFFFFFFFL;
        }
    }

    /**
     * Convert market string to market ID
     */
    public int getMarketId() {
        // Currently only BTC-USD is supported
        return MARKET_BTC_USD;
    }

    /**
     * Get price as fixed-point long (8 decimals)
     */
    public long getPriceAsLong() {
        return FixedPoint.fromDouble(price);
    }

    /**
     * Get quantity as fixed-point long (8 decimals)
     */
    public long getQuantityAsLong() {
        return FixedPoint.fromDouble(quantity);
    }

    /**
     * Get total price as fixed-point long (8 decimals)
     */
    public long getTotalPriceAsLong() {
        return FixedPoint.fromDouble(totalPrice);
    }

    public OrderType toOrderType() {
        if (orderType == null) return OrderType.LIMIT;
        switch (orderType) {
            case "LIMIT":
                return OrderType.LIMIT;
            case "MARKET":
                return OrderType.MARKET;
            case "LIMIT_MAKER":
                return OrderType.LIMIT_MAKER;
            default:
                return OrderType.LIMIT;
        }
    }

    public OrderSide toOrderSide() {
        if (orderSide == null) return OrderSide.BID;
        switch (orderSide) {
            case "ASK":
                return OrderSide.ASK;
            case "BID":
                return OrderSide.BID;
            default:
                return OrderSide.BID;
        }
    }
}
