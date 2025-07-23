package com.match.application.engine;

import com.match.application.handlers.LimitMakerOrderHandler;
import com.match.application.handlers.LimitOrderHandler;
import com.match.application.handlers.MarketOrderHandler;
import com.match.application.orderbook.OrderBookImpl;
import com.match.application.orderbook.OrderBookSideImpl;
import com.match.domain.*;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.commands.UpdateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.domain.interfaces.OrderBook;
import com.match.domain.interfaces.OrderBookSide;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.match.infrastructure.Logger;

public class Engine {
    private static final Logger logger = Logger.getLogger(Engine.class);
    private final Map<String, OrderBook> books;

    public Engine() {
        this.books = new HashMap<>();

        // Create a new order book for BTC-USD
        OrderBook orderbook = new OrderBookImpl("BTC-USD", "BTC", "USD");

        // Create the ask and bid sides
        OrderBookSide askSide = new OrderBookSideImpl(Comparator.naturalOrder()); // Ascending prices
        OrderBookSide bidSide = new OrderBookSideImpl(Comparator.reverseOrder()); // Descending prices

        // Register handlers for each order type
        LimitOrderHandler limitOrderHandler = new LimitOrderHandler();
        MarketOrderHandler marketOrderHandler = new MarketOrderHandler();
        LimitMakerOrderHandler limitMakerOrderHandler = new LimitMakerOrderHandler();

        askSide.registerHandler(OrderType.LIMIT, limitOrderHandler);
        askSide.registerHandler(OrderType.MARKET, marketOrderHandler);
        askSide.registerHandler(OrderType.LIMIT_MAKER, limitMakerOrderHandler);

        bidSide.registerHandler(OrderType.LIMIT, limitOrderHandler);
        bidSide.registerHandler(OrderType.MARKET, marketOrderHandler);
        bidSide.registerHandler(OrderType.LIMIT_MAKER, limitMakerOrderHandler);

        // Register the sides with the order book
        orderbook.registerSide(OrderSide.ASK, askSide);
        orderbook.registerSide(OrderSide.BID, bidSide);

        // Register the order book with the engine
        this.register("BTC-USD", orderbook);

        logger.info("Engine started and BTC-USD order book is ready.");
    }

    public void register(String id, OrderBook orderbook) {
        if (!books.containsKey(id)) {
            books.put(id, orderbook);
        }
    }

    public OrderBook getBook(String id) {
        return books.get(id);
    }

    public void acceptOrder(String market, String type, Object command) throws Exception {
        OrderBook ob = getBook(market);
        if (ob == null) {
            logger.warn("book not found: %s", market);
            return;
        }
        if ("create".equals(type)) {
            CreateOrderCommand c = (CreateOrderCommand) command;
            Order order = new Order();
            order.setId(UUID.randomUUID().toString());
            order.setUserId(c.getUserId());
            order.setPrice(c.getPrice());
            order.setQuantity(c.getQuantity());
            order.setTotalPrice(c.getTotalPrice());
            order.setSide(c.getOrderSide());
            order.setRemainingQuantity(c.getQuantity());
            order.setMarket(market);
            order.setType(c.getOrderType());
            order.setAcceptedAt(java.time.LocalDateTime.now());
            ob.createOrder(order);
        } else if ("cancel".equals(type)) {
            CancelOrderCommand c = (CancelOrderCommand) command;
            ob.cancelOrder(c.getOrderId());
        } else if ("update".equals(type)) {
            UpdateOrderCommand c = (UpdateOrderCommand) command;
            ob.updateOrder(c.getOrderId());
        }
    }

    public void close(){
        // No longer needed as we don't have threads to stop
    }
} 