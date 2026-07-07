# market-relay

Edge fan-out for the public market-data WebSocket. The match gateway
publishes each frame ONCE over an outbound WebSocket (`EdgePublisher`,
enabled by `MARKET_EDGE_URL` + `MARKET_EDGE_TOKEN(_FILE)`); a Cloudflare
Durable Object caches subscribe-time state and rebroadcasts to every
viewer at the edge. Origin upload stays O(1) in viewer count.

The relay speaks the exact viewer protocol `MarketDataWebSocket` serves on
`:8081/ws` (subscribe/refresh/ping, BOOK_SNAPSHOT/BOOK_DELTA chain,
TRADES_BATCH, TICKER_STATS, CANDLE_HISTORY), so trading-ui connects
unchanged. Late joiners get the cached snapshot plus a bounded delta
replay, keeping the bookVersion chain gapless; genuine holes heal through
the client's existing refresh path.

## Layout

- `src/index.ts` — Worker: `/ws` (viewers), `/publish` (gateway, bearer
  token), `/healthz` (relay stats)
- `src/feed.ts` — the `MarketFeed` Durable Object (Hibernation API; one
  instance carries all markets)

## Deploy

```bash
npm install
npm test
npx wrangler deploy
# once: printf '%s' "$TOKEN" | npx wrangler secret put PUBLISH_TOKEN
```

Production cutover/rollback = the `routes` entry in `wrangler.jsonc`
(`market.openexch.io/ws*`): with the route deployed the Worker intercepts
only the WebSocket path (REST keeps flowing through the tunnel); removing
the route reverts to direct serving instantly. The gateway keeps serving
`/ws` directly either way — the relay is an overlay, not a replacement.
