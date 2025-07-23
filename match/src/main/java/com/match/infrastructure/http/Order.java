package com.match.infrastructure.http;

// Order.java
// Bu sınıf, HTTP isteğinden gelen JSON verisini temsil eder.

import com.match.infrastructure.generated.sbe.OrderSide;
import com.match.infrastructure.generated.sbe.OrderType;

public class Order {
    // JSON alanları ile aynı isimde olmalıdırlar.
    String userId;
    String market;
    String orderType; // e.g., "LIMIT", "MARKET"
    String orderSide; // e.g., "BUY", "SELL"
    double price;
    double quantity;
    double totalPrice;
    long timestamp; // Latency hesaplaması için timestamp

    // Gson'un bu sınıfı kullanabilmesi için boş bir constructor faydalıdır.
    public Order() {
    }

    // Konsolda kolayca yazdırmak için bir toString metodu ekleyelim.
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

    public OrderType toOrderType() {
        switch (orderType) {
            case "LIMIT":
                return OrderType.LIMIT;
            case "MARKET":
                return OrderType.MARKET;
            case "LIMIT_MAKER":
                return OrderType.LIMIT_MAKER;
        }
        return OrderType.LIMIT;
    }

    public OrderSide toOrderSide() {
        switch (orderSide) {
            case "ASK":
                return OrderSide.ASK;
            case "BID":
                return OrderSide.BID;
        }
        return OrderSide.BID;
    }
}