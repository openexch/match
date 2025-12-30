package com.match.loadtest;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Order generation scenarios that simulate realistic market behavior
 */
public enum OrderScenario {

    /**
     * Balanced mix of limit and market orders on both sides
     * Simulates normal market conditions
     */
    BALANCED("Balanced Trading") {
        @Override
        public OrderParams generateOrder(String market, double midPrice) {
            Random rand = ThreadLocalRandom.current();

            // 70% limit orders, 30% market orders
            boolean isLimit = rand.nextDouble() < 0.70;

            // 50/50 bid/ask
            boolean isBid = rand.nextBoolean();

            // Price spread around mid price (0.1% - 0.5%)
            double spread = 0.001 + (rand.nextDouble() * 0.004);
            double price = isBid ?
                midPrice * (1.0 - spread) :
                midPrice * (1.0 + spread);

            // Quantity: 0.001 to 1.0 BTC
            double quantity = 0.001 + (rand.nextDouble() * 0.999);

            return new OrderParams(
                isLimit ? "LIMIT" : "MARKET",
                isBid ? "BID" : "ASK",
                isLimit ? price : 0.0,
                quantity,
                market
            );
        }
    },

    /**
     * High volume of limit orders with tight spreads
     * Simulates market maker activity
     */
    MARKET_MAKER("Market Maker") {
        @Override
        public OrderParams generateOrder(String market, double midPrice) {
            Random rand = ThreadLocalRandom.current();

            // 95% limit orders, 5% cancellations (market orders for simplicity)
            boolean isLimit = rand.nextDouble() < 0.95;

            // 50/50 bid/ask
            boolean isBid = rand.nextBoolean();

            // Very tight spread (0.01% - 0.1%)
            double spread = 0.0001 + (rand.nextDouble() * 0.0009);
            double price = isBid ?
                midPrice * (1.0 - spread) :
                midPrice * (1.0 + spread);

            // Smaller quantities: 0.01 to 0.5 BTC
            double quantity = 0.01 + (rand.nextDouble() * 0.49);

            return new OrderParams(
                isLimit ? "LIMIT" : "MARKET",
                isBid ? "BID" : "ASK",
                isLimit ? price : 0.0,
                quantity,
                market
            );
        }
    },

    /**
     * High ratio of market orders (aggressive trading)
     * Simulates high volatility periods
     */
    AGGRESSIVE("Aggressive Trading") {
        @Override
        public OrderParams generateOrder(String market, double midPrice) {
            Random rand = ThreadLocalRandom.current();

            // 60% market orders, 40% limit orders
            boolean isLimit = rand.nextDouble() < 0.40;

            // Slight bias towards one side (55/45)
            boolean isBid = rand.nextDouble() < 0.55;

            // Wider spread for limit orders (0.2% - 1.0%)
            double spread = 0.002 + (rand.nextDouble() * 0.008);
            double price = isBid ?
                midPrice * (1.0 - spread) :
                midPrice * (1.0 + spread);

            // Larger quantities: 0.1 to 2.0 BTC
            double quantity = 0.1 + (rand.nextDouble() * 1.9);

            return new OrderParams(
                isLimit ? "LIMIT" : "MARKET",
                isBid ? "BID" : "ASK",
                isLimit ? price : 0.0,
                quantity,
                market
            );
        }
    },

    /**
     * Simulates sudden spikes in order volume
     * Burst periods with calm periods
     */
    SPIKE("Spike Testing") {
        private volatile boolean inSpike = false;
        private volatile long spikeStartTime = 0;
        private static final long SPIKE_DURATION_MS = 5000; // 5 second spikes
        private static final long CALM_DURATION_MS = 15000; // 15 second calm

        @Override
        public OrderParams generateOrder(String market, double midPrice) {
            Random rand = ThreadLocalRandom.current();

            // Toggle spike mode
            long now = System.currentTimeMillis();
            if (!inSpike && now - spikeStartTime > CALM_DURATION_MS) {
                inSpike = true;
                spikeStartTime = now;
            } else if (inSpike && now - spikeStartTime > SPIKE_DURATION_MS) {
                inSpike = false;
                spikeStartTime = now;
            }

            // During spike: more aggressive market orders
            // During calm: more limit orders
            boolean isLimit = inSpike ?
                rand.nextDouble() < 0.30 :
                rand.nextDouble() < 0.80;

            boolean isBid = rand.nextBoolean();

            double spread = inSpike ?
                0.003 + (rand.nextDouble() * 0.007) :
                0.001 + (rand.nextDouble() * 0.004);

            double price = isBid ?
                midPrice * (1.0 - spread) :
                midPrice * (1.0 + spread);

            double quantity = inSpike ?
                0.5 + (rand.nextDouble() * 1.5) :
                0.01 + (rand.nextDouble() * 0.49);

            return new OrderParams(
                isLimit ? "LIMIT" : "MARKET",
                isBid ? "BID" : "ASK",
                isLimit ? price : 0.0,
                quantity,
                market
            );
        }
    },

    /**
     * Deep order book building scenario
     * Creates many limit orders at various price levels
     */
    DEEP_BOOK("Deep Order Book") {
        @Override
        public OrderParams generateOrder(String market, double midPrice) {
            Random rand = ThreadLocalRandom.current();

            // 90% limit orders
            boolean isLimit = rand.nextDouble() < 0.90;

            boolean isBid = rand.nextBoolean();

            // Wider price range (0.1% - 2.0%)
            double spread = 0.001 + (rand.nextDouble() * 0.019);
            double price = isBid ?
                midPrice * (1.0 - spread) :
                midPrice * (1.0 + spread);

            // Varying quantities
            double quantity = 0.001 + (rand.nextDouble() * 0.999);

            return new OrderParams(
                isLimit ? "LIMIT" : "MARKET",
                isBid ? "BID" : "ASK",
                isLimit ? price : 0.0,
                quantity,
                market
            );
        }
    };

    private final String name;

    OrderScenario(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * Generate an order based on this scenario's characteristics
     * @param market The market pair (e.g., "BTC-USD")
     * @param midPrice Current mid-market price
     * @return Generated order parameters
     */
    public abstract OrderParams generateOrder(String market, double midPrice);

    /**
     * Container for generated order parameters
     */
    public static class OrderParams {
        public final String orderType;
        public final String orderSide;
        public final double price;
        public final double quantity;
        public final String market;

        public OrderParams(String orderType, String orderSide, double price, double quantity, String market) {
            this.orderType = orderType;
            this.orderSide = orderSide;
            this.price = price;
            this.quantity = quantity;
            this.market = market;
        }

        public double getTotalPrice() {
            return price * quantity;
        }
    }
}
