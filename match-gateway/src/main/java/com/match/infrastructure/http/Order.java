package com.match.infrastructure.http;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.OrderSide;
import com.match.infrastructure.generated.OrderType;

import java.util.Map;

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

    // Market symbol to ID mapping
    private static final Map<String, Integer> MARKET_IDS = Map.of(
        "BTC-USD", 1,
        "ETH-USD", 2,
        "SOL-USD", 3,
        "XRP-USD", 4,
        "DOGE-USD", 5
    );

    public Order() {
    }

    // ==================== Validation ====================

    /**
     * Validate order fields. Returns null if valid, error message if invalid.
     * All validation happens HERE at the gateway — engine stays clean and fast.
     */
    public String validate() {
        // Market is required and must be known
        if (market == null || market.isEmpty()) {
            return "market is required";
        }
        if (!MARKET_IDS.containsKey(market)) {
            return "unknown market: " + market + ". Valid: " + MARKET_IDS.keySet();
        }

        // Order side is required
        if (orderSide == null || orderSide.isEmpty()) {
            return "orderSide is required (BUY/SELL)";
        }
        String side = orderSide.toUpperCase();
        if (!side.equals("BUY") && !side.equals("SELL") && !side.equals("BID") && !side.equals("ASK")) {
            return "invalid orderSide: " + orderSide + ". Valid: BUY, SELL, BID, ASK";
        }

        // Order type defaults to LIMIT but must be valid if provided
        if (orderType != null && !orderType.isEmpty()) {
            if (!orderType.equals("LIMIT") && !orderType.equals("MARKET") && !orderType.equals("LIMIT_MAKER")) {
                return "invalid orderType: " + orderType + ". Valid: LIMIT, MARKET, LIMIT_MAKER";
            }
        }

        // Quantity must be positive
        if (quantity <= 0) {
            return "quantity must be positive, got: " + quantity;
        }

        // Quantity sanity bound (prevent fixed-point overflow: qty * SCALE_FACTOR must fit long)
        if (quantity > 90_000_000_000.0) { // ~9e10, well under Long.MAX_VALUE / SCALE_FACTOR
            return "quantity too large: " + quantity;
        }

        String effectiveType = (orderType == null || orderType.isEmpty()) ? "LIMIT" : orderType;

        if (effectiveType.equals("MARKET")) {
            // Market buy needs totalPrice (budget), market sell just needs quantity
            if (side.equals("BUY") || side.equals("BID")) {
                if (totalPrice <= 0) {
                    return "market buy requires positive totalPrice (budget)";
                }
                if (totalPrice > 90_000_000_000.0) {
                    return "totalPrice too large: " + totalPrice;
                }
            }
        } else {
            // Limit orders need a positive price
            if (price <= 0) {
                return "limit order requires positive price, got: " + price;
            }
            if (price > 90_000_000_000.0) {
                return "price too large: " + price;
            }
        }

        // UserId
        if (userId == null || userId.isEmpty()) {
            return "userId is required";
        }

        return null; // Valid
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
        return MARKET_IDS.getOrDefault(market, 0);
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
        switch (orderSide.toUpperCase()) {
            case "ASK":
            case "SELL":
                return OrderSide.ASK;
            case "BID":
            case "BUY":
                return OrderSide.BID;
            default:
                return OrderSide.BID;
        }
    }
}
