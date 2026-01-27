package com.match.application.handlers;

import com.match.domain.Order;
import com.match.domain.interfaces.OrderBookSide;
import java.util.Collections;

public class LimitMakerOrderHandler implements OrderBookSide.OrderTypeSideHandler {

    @Override
    public OrderBookSide.MatchResult handle(OrderBookSide side, Order taker) {
        // Limit Maker orders (post-only) are rejected if they would match immediately.
        if (side.canMatch(taker)) {
            // Reject the order by returning no matches and no order to place.
            return new OrderBookSide.MatchResult(Collections.emptyList(), null);
        } else {
            // The order does not match, so it can be placed on the book.
            return new OrderBookSide.MatchResult(Collections.emptyList(), taker);
        }
    }
} 