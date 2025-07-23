package com.match.domain;

import java.math.BigDecimal;

public class OrderMatch {
    private Order taker;
    private Order maker;
    private BigDecimal price;
    private BigDecimal quantity;

    public OrderMatch(Order taker, Order maker, BigDecimal price, BigDecimal quantity) {
        this.taker = taker;
        this.maker = maker;
        this.price = price;
        this.quantity = quantity;
    }

    public Order getTaker() {
        return taker;
    }

    public void setTaker(Order taker) {
        this.taker = taker;
    }

    public Order getMaker() {
        return maker;
    }

    public void setMaker(Order maker) {
        this.maker = maker;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }
} 