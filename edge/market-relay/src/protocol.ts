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

function tradeTimestamp(t: unknown): number {
  const ts = (t as { timestamp?: unknown } | null)?.timestamp;
  return typeof ts === 'number' ? ts : 0;
}

/** Identity of a trade for de-dup across a bundle replace and a live append.
 *  There is no trade id on the wire, so key on the aggregated trade's fields;
 *  a bundle and a live batch carry byte-equal values for the same trade. */
function tradeKey(t: unknown): string {
  const o = (t ?? {}) as Record<string, unknown>;
  return `${o.timestamp}|${o.price}|${o.quantity}|${o.side ?? ''}|${o.tradeCount ?? ''}`;
}

/**
 * Merge an authoritative bundle tape into the live-teed one WITHOUT letting an
 * older bundle clobber a newer live-appended trade (match#99 item 4). Union +
 * de-dup by trade key, keep oldest-first by timestamp, retain the newest `cap`.
 */
export function mergeRecentTrades(existing: unknown[], incoming: unknown[], cap = RECENT_TRADES_CAP): unknown[] {
  const seen = new Set<string>();
  const merged: unknown[] = [];
  for (const t of existing) {
    const k = tradeKey(t);
    if (!seen.has(k)) {
      seen.add(k);
      merged.push(t);
    }
  }
  for (const t of incoming) {
    const k = tradeKey(t);
    if (!seen.has(k)) {
      seen.add(k);
      merged.push(t);
    }
  }
  merged.sort((a, b) => tradeTimestamp(a) - tradeTimestamp(b));
  return merged.length > cap ? merged.slice(merged.length - cap) : merged;
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
