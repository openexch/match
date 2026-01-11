● Why JSON in egress is not optimal:

The current implementation uses JSON strings for cluster egress messages:
- message.getBytes(StandardCharsets.UTF_8) - string allocation + encoding
- JSON parsing on the gateway side adds latency
- Larger message sizes than binary

Best Practice for Ultra-Low Latency:

1. Use SBE (Simple Binary Encoding) for ALL cluster messages - you already have SBE schemas and encoders
2. Define SBE messages for:
   - TradeExecution (orderId, matchId, price, quantity, timestamp)
   - OrderStatus (orderId, status, filledQty)
   - MarketDataUpdate (bid, ask, lastPrice, volume)
3. Gateway decodes SBE directly and only converts to JSON at the WebSocket boundary (for browser clients)

This would eliminate string allocations in the critical path.
