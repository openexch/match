// Wire shapes shared with match-gateway's MarketDataWebSocket. The relay
// speaks the exact viewer protocol the gateway serves on :8081/ws, so
// trading-ui connects unchanged. Only the fields the relay inspects are
// declared; frames are re-sent as the raw strings the gateway produced.

/** A client -> server message ({"action":"subscribe","marketId":1} etc). */
export interface ViewerMessage {
  action?: string;
  marketId?: number;
}

/** Any gateway frame. The relay routes on `type`/`marketId` and tracks the
 *  book-version chain (`bookVersion`/`fromVersion`) for delta replay. */
export interface Frame {
  type?: string;
  marketId?: number;
  market?: string;
  bookVersion?: number;
  version?: number;
  fromVersion?: number;
  trades?: unknown[];
  /** EDGE_CACHE envelope: the wrapped frame to cache without broadcasting. */
  frame?: Frame;
}

/** Trades the relay keeps per market to serve the subscribe-time tape. */
export const RECENT_TRADES_CAP = 50;

/** Replay-buffer bound; a hole past this age is healed by client refresh. */
export const DELTA_BUFFER_CAP = 1000;

export function frameMarketId(f: Frame): number | undefined {
  return typeof f.marketId === 'number' ? f.marketId : undefined;
}

/** Mirrors the gateway's buildEmptyBookSnapshot (bookVersion omitted). */
export function emptyBookSnapshot(marketId: number, market: string): string {
  return JSON.stringify({
    type: 'BOOK_SNAPSHOT',
    marketId,
    market,
    timestamp: Date.now(),
    bidVersion: 0,
    askVersion: 0,
    version: 0,
    bids: [],
    asks: [],
  });
}
