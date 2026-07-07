import { DurableObject } from 'cloudflare:workers';
import {
  DELTA_BUFFER_CAP,
  RECENT_TRADES_CAP,
  emptyBookSnapshot,
  frameMarketId,
  type Frame,
  type ViewerMessage,
} from './protocol';
import type { Env } from './index';

/** Per-market cache: everything a subscriber needs at connect time, kept
 *  fresh by the gateway's live tee + periodic EDGE_CACHE bundles. */
interface MarketState {
  market: string;
  /** Latest BOOK_SNAPSHOT frame, verbatim. */
  snapshot: string | null;
  snapshotVersion: number;
  /** Latest TICKER_STATS frame, verbatim. */
  ticker: string | null;
  /** Latest CANDLE_HISTORY frame (1m, 200 buckets), verbatim. */
  candles: string | null;
  /** Merged recent trade entries, oldest first, capped. */
  trades: unknown[];
}

/** BOOK_DELTAs since the cached snapshot, in arrival order. Replayed to a
 *  joining viewer after the snapshot so the version chain has no hole
 *  (the UI stitches: drops deltas <= snapshot version, verifies the rest). */
interface DeltaEntry {
  to: number;
  raw: string;
}

const PERSISTED_FIELDS: (keyof MarketState)[] = ['market', 'snapshot', 'snapshotVersion', 'ticker', 'candles', 'trades'];

/**
 * One instance ("feed") relays all markets: the match gateway publishes each
 * frame once on the tagged 'pub' socket; viewers subscribe per market over
 * the same protocol the gateway serves directly. Caches are persisted so a
 * restarted/evicted DO serves subscribes instantly; the delta buffers are
 * ephemeral (a hole is healed by the client's own refresh path).
 */
export class MarketFeed extends DurableObject<Env> {
  private markets = new Map<number, MarketState>();
  private deltas = new Map<number, DeltaEntry[]>();
  /** viewer socket -> subscribed marketId (rebuilt from attachments on wake) */
  private viewers = new Map<WebSocket, number>();
  private lastPublishMs = 0;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // The UI's keepalive is the exact string '{"action":"ping"}'; answer it
    // without waking the object. (webSocketMessage also answers any other
    // ping spelling.)
    ctx.setWebSocketAutoResponse(
      new WebSocketRequestResponsePair('{"action":"ping"}', JSON.stringify({ type: 'PONG', timestamp: 0 }))
    );
    ctx.blockConcurrencyWhile(async () => {
      const stored = await ctx.storage.list<MarketState>({ prefix: 'mkt:' });
      for (const [key, state] of stored) {
        this.markets.set(Number(key.slice(4)), state);
      }
      for (const ws of ctx.getWebSockets('viewer')) {
        const att = ws.deserializeAttachment() as { m?: number } | null;
        if (att && typeof att.m === 'number') this.viewers.set(ws, att.m);
      }
    });
  }

  // ── connection intake (via the Worker's fetch) ──────────────────────────

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
      return new Response('WebSocket upgrade required', { status: 426 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    // The Worker authenticates /publish before forwarding; the DO is not
    // directly reachable, so the tag is the trust boundary here.
    this.ctx.acceptWebSocket(server, [path === '/publish' ? 'pub' : 'viewer']);
    return new Response(null, { status: 101, webSocket: client });
  }

  // ── hibernation handlers ────────────────────────────────────────────────

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== 'string') return;
    if (this.isPublisher(ws)) {
      this.onPublisherFrame(message);
      return;
    }
    let msg: ViewerMessage;
    try {
      msg = JSON.parse(message) as ViewerMessage;
    } catch {
      ws.send(JSON.stringify({ type: 'ERROR', message: `Invalid message: ${message.slice(0, 128)}` }));
      return;
    }
    this.onViewerMessage(ws, msg);
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    this.viewers.delete(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.viewers.delete(ws);
  }

  // ── publisher side ──────────────────────────────────────────────────────

  private onPublisherFrame(raw: string): void {
    let frame: Frame;
    try {
      frame = JSON.parse(raw) as Frame;
    } catch {
      return;
    }
    this.lastPublishMs = Date.now();

    if (frame.type === 'EDGE_CACHE') {
      if (frame.frame) this.cacheFrame(frame.frame, true);
      return;
    }

    this.cacheFrame(frame, false);

    const marketId = frameMarketId(frame);
    if (marketId === undefined) {
      // CLUSTER_STATUS / CLUSTER_EVENT: every viewer, like the gateway does.
      for (const viewer of this.viewers.keys()) this.trySend(viewer, raw);
      return;
    }
    for (const [viewer, m] of this.viewers) {
      if (m === marketId) this.trySend(viewer, raw);
    }
  }

  /** Update caches from a frame; `bundle` frames also persist the market. */
  private cacheFrame(frame: Frame, bundle: boolean): void {
    const marketId = frameMarketId(frame);
    if (marketId === undefined) return;
    const state = this.marketState(marketId, frame.market);
    if (frame.market) state.market = frame.market;

    switch (frame.type) {
      case 'BOOK_SNAPSHOT': {
        state.snapshot = JSON.stringify(frame);
        state.snapshotVersion = frame.bookVersion ?? frame.version ?? 0;
        const buf = this.deltas.get(marketId);
        if (buf) this.deltas.set(marketId, buf.filter((d) => d.to > state.snapshotVersion));
        break;
      }
      case 'BOOK_DELTA': {
        const to = frame.bookVersion ?? 0;
        const buf = this.deltas.get(marketId) ?? [];
        buf.push({ to, raw: JSON.stringify(frame) });
        if (buf.length > DELTA_BUFFER_CAP) buf.shift();
        this.deltas.set(marketId, buf);
        return; // deltas are never persisted
      }
      case 'TICKER_STATS':
        state.ticker = JSON.stringify(frame);
        break;
      case 'CANDLE_HISTORY':
        state.candles = JSON.stringify(frame);
        break;
      case 'TRADES_BATCH': {
        if (!Array.isArray(frame.trades)) break;
        // Bundles carry the gateway's authoritative recent tape: replace.
        // Live batches append (oldest-first order matches the ring buffer).
        state.trades = bundle ? [...frame.trades] : [...state.trades, ...frame.trades];
        if (state.trades.length > RECENT_TRADES_CAP) {
          state.trades = state.trades.slice(state.trades.length - RECENT_TRADES_CAP);
        }
        break;
      }
      default:
        return;
    }

    if (bundle) {
      const persisted: Partial<MarketState> = {};
      for (const f of PERSISTED_FIELDS) (persisted as Record<string, unknown>)[f] = state[f];
      void this.ctx.storage.put(`mkt:${marketId}`, persisted);
    }
  }

  // ── viewer side (mirrors MarketDataWebSocket.channelRead0) ──────────────

  private onViewerMessage(ws: WebSocket, msg: ViewerMessage): void {
    const marketId = typeof msg.marketId === 'number' ? msg.marketId : this.viewers.get(ws);
    switch (msg.action) {
      case 'subscribe': {
        if (typeof msg.marketId !== 'number') {
          ws.send(JSON.stringify({ type: 'ERROR', message: 'subscribe requires marketId' }));
          return;
        }
        this.viewers.set(ws, msg.marketId);
        ws.serializeAttachment({ m: msg.marketId });
        ws.send(JSON.stringify({ type: 'SUBSCRIPTION_CONFIRMED', marketId: msg.marketId }));
        const state = this.markets.get(msg.marketId);
        if (state?.ticker) ws.send(state.ticker);
        this.sendInitialState(ws, msg.marketId);
        return;
      }
      case 'refresh': {
        if (marketId === undefined) {
          ws.send(JSON.stringify({ type: 'REFRESH_PENDING', message: 'State not yet available, waiting for cluster update' }));
          return;
        }
        this.sendInitialState(ws, marketId);
        return;
      }
      case 'unsubscribe': {
        this.viewers.delete(ws);
        ws.serializeAttachment({});
        ws.send(JSON.stringify({ type: 'UNSUBSCRIPTION_CONFIRMED', marketId }));
        return;
      }
      case 'ping':
        ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
        return;
      case 'getOrderBook':
        if (marketId !== undefined) this.sendBook(ws, marketId);
        return;
      case 'getTrades':
        if (marketId !== undefined) this.sendTrades(ws, marketId);
        return;
      default:
        ws.send(JSON.stringify({ type: 'ERROR', message: `Invalid message: unknown action ${String(msg.action)}` }));
    }
  }

  /** Book (+ chain replay), recent trades, candle history: the same trio the
   *  gateway's sendInitialState pushes on subscribe/refresh. */
  private sendInitialState(ws: WebSocket, marketId: number): void {
    this.sendBook(ws, marketId);
    this.sendTrades(ws, marketId);
    const state = this.markets.get(marketId);
    if (state?.candles) ws.send(state.candles);
  }

  private sendBook(ws: WebSocket, marketId: number): void {
    const state = this.markets.get(marketId);
    ws.send(state?.snapshot ?? emptyBookSnapshot(marketId, state?.market ?? ''));
    for (const d of this.deltas.get(marketId) ?? []) ws.send(d.raw);
  }

  private sendTrades(ws: WebSocket, marketId: number): void {
    const state = this.markets.get(marketId);
    if (!state) return;
    ws.send(
      JSON.stringify({
        type: 'TRADES_BATCH',
        marketId,
        market: state.market,
        timestamp: Date.now(),
        trades: state.trades,
      })
    );
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private isPublisher(ws: WebSocket): boolean {
    return this.ctx.getTags(ws).includes('pub');
  }

  private marketState(marketId: number, market?: string): MarketState {
    let state = this.markets.get(marketId);
    if (!state) {
      state = { market: market ?? '', snapshot: null, snapshotVersion: 0, ticker: null, candles: null, trades: [] };
      this.markets.set(marketId, state);
    }
    return state;
  }

  private trySend(ws: WebSocket, raw: string): void {
    try {
      ws.send(raw);
    } catch {
      this.viewers.delete(ws);
    }
  }

  /** Health surface for the Worker's /healthz. */
  async stats(): Promise<Record<string, unknown>> {
    const markets: Record<string, unknown> = {};
    for (const [id, s] of this.markets) {
      markets[id] = {
        market: s.market,
        snapshotVersion: s.snapshotVersion,
        trades: s.trades.length,
        deltasBuffered: this.deltas.get(id)?.length ?? 0,
        hasTicker: s.ticker !== null,
        hasCandles: s.candles !== null,
      };
    }
    return {
      viewers: this.viewers.size,
      publishers: this.ctx.getWebSockets('pub').length,
      lastPublishAgeMs: this.lastPublishMs ? Date.now() - this.lastPublishMs : null,
      markets,
    };
  }
}
