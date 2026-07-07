import { SELF } from 'cloudflare:test';
import { describe, expect, it } from 'vitest';

const PUB_TOKEN = 'test-publish-token';

async function connect(path: string, headers: Record<string, string> = {}): Promise<WebSocket> {
  const res = await SELF.fetch(`https://relay.test${path}`, {
    headers: { Upgrade: 'websocket', ...headers },
  });
  expect(res.status).toBe(101);
  const ws = res.webSocket!;
  ws.accept();
  return ws;
}

function connectPublisher(): Promise<WebSocket> {
  return connect('/publish', { Authorization: `Bearer ${PUB_TOKEN}` });
}

/** Collect the next `n` messages from a socket (order-preserving). */
function take(ws: WebSocket, n: number, timeoutMs = 2000): Promise<string[]> {
  return new Promise((resolve, reject) => {
    const got: string[] = [];
    const timer = setTimeout(() => reject(new Error(`timeout: got ${got.length}/${n}: ${got.join('|')}`)), timeoutMs);
    ws.addEventListener('message', function handler(ev) {
      got.push(ev.data as string);
      if (got.length === n) {
        clearTimeout(timer);
        ws.removeEventListener('message', handler);
        resolve(got);
      }
    });
  });
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function bundle(frame: unknown): string {
  return JSON.stringify({ type: 'EDGE_CACHE', frame });
}

const SNAP_1 = {
  type: 'BOOK_SNAPSHOT', marketId: 1, market: 'BTC-USD', timestamp: 1, bidVersion: 100, askVersion: 99,
  version: 100, bookVersion: 100, bids: [{ price: 100.0, quantity: 1.0, orderCount: 1 }], asks: [],
};
const TICKER_1 = {
  type: 'TICKER_STATS', marketId: 1, market: 'BTC-USD', lastPrice: 100.0, priceChange: 1.0,
  priceChangePercent: 1.0, high24h: 101.0, low24h: 99.0, volume24h: 5.0, timestamp: 1,
};
const CANDLES_1 = { type: 'CANDLE_HISTORY', marketId: 1, market: 'BTC-USD', interval: '1m', candles: [] };
const TRADES_1 = {
  type: 'TRADES_BATCH', marketId: 1, market: 'BTC-USD', timestamp: 1,
  trades: [{ price: 100.0, quantity: 0.5, tradeCount: 1, timestamp: 1, side: 'BUY' }],
};

async function seedMarket1(pub: WebSocket): Promise<void> {
  pub.send(bundle(SNAP_1));
  pub.send(bundle(TICKER_1));
  pub.send(bundle(CANDLES_1));
  pub.send(bundle(TRADES_1));
  await sleep(50);
}

describe('publish auth', () => {
  it('rejects /publish without a token', async () => {
    const res = await SELF.fetch('https://relay.test/publish', { headers: { Upgrade: 'websocket' } });
    expect(res.status).toBe(401);
  });

  it('rejects /publish with a wrong token', async () => {
    const res = await SELF.fetch('https://relay.test/publish', {
      headers: { Upgrade: 'websocket', Authorization: 'Bearer nope' },
    });
    expect(res.status).toBe(401);
  });
});

describe('subscribe bootstrap', () => {
  it('serves confirmed + ticker + book + trades + candles from cache, gateway order', async () => {
    const pub = await connectPublisher();
    await seedMarket1(pub);

    const viewer = await connect('/ws');
    const frames = take(viewer, 5);
    viewer.send(JSON.stringify({ action: 'subscribe', marketId: 1 }));
    const types = (await frames).map((f) => JSON.parse(f).type);
    expect(types).toEqual(['SUBSCRIPTION_CONFIRMED', 'TICKER_STATS', 'BOOK_SNAPSHOT', 'TRADES_BATCH', 'CANDLE_HISTORY']);
  });

  it('serves an empty book snapshot for a market with no data yet', async () => {
    const viewer = await connect('/ws');
    const two = take(viewer, 2); // confirmed + empty snapshot; nothing else is cached
    viewer.send(JSON.stringify({ action: 'subscribe', marketId: 43 }));
    const [confirmed, snap] = (await two).map((f) => JSON.parse(f));
    expect(confirmed.type).toBe('SUBSCRIPTION_CONFIRMED');
    expect(snap).toMatchObject({ type: 'BOOK_SNAPSHOT', marketId: 43, version: 0, bids: [], asks: [] });
    expect('bookVersion' in snap).toBe(false);
  });
});

describe('live fan-out', () => {
  it('routes market frames to same-market viewers only, cluster frames to all', async () => {
    const pub = await connectPublisher();
    await seedMarket1(pub);

    const v1 = await connect('/ws');
    const boot1 = take(v1, 5);
    v1.send(JSON.stringify({ action: 'subscribe', marketId: 1 }));
    await boot1;

    const v2 = await connect('/ws');
    const boot2 = take(v2, 2);
    v2.send(JSON.stringify({ action: 'subscribe', marketId: 2 }));
    await boot2;

    const next1 = take(v1, 2);
    const next2 = take(v2, 1);
    pub.send(JSON.stringify({ type: 'BOOK_DELTA', marketId: 1, market: 'BTC-USD', bookVersion: 101, fromVersion: 100, changes: [] }));
    pub.send(JSON.stringify({ type: 'CLUSTER_EVENT', event: 'LEADER_CHANGE', newLeaderId: 1, timestamp: 2 }));

    const got1 = (await next1).map((f) => JSON.parse(f).type);
    const got2 = (await next2).map((f) => JSON.parse(f).type);
    expect(got1).toEqual(['BOOK_DELTA', 'CLUSTER_EVENT']);
    expect(got2).toEqual(['CLUSTER_EVENT']); // no market-1 delta for the market-2 viewer
  });
});

describe('delta replay chain', () => {
  it('replays buffered deltas after the snapshot so late joiners see no gap', async () => {
    const pub = await connectPublisher();
    await seedMarket1(pub);
    pub.send(JSON.stringify({ type: 'BOOK_DELTA', marketId: 1, bookVersion: 101, fromVersion: 100, changes: [] }));
    pub.send(JSON.stringify({ type: 'BOOK_DELTA', marketId: 1, bookVersion: 102, fromVersion: 101, changes: [] }));
    await sleep(50);

    const viewer = await connect('/ws');
    const frames = take(viewer, 7); // confirmed, ticker, snapshot, delta 101, delta 102, trades, candles
    viewer.send(JSON.stringify({ action: 'subscribe', marketId: 1 }));
    const parsed = (await frames).map((f) => JSON.parse(f));
    expect(parsed.map((p) => p.type)).toEqual([
      'SUBSCRIPTION_CONFIRMED', 'TICKER_STATS', 'BOOK_SNAPSHOT', 'BOOK_DELTA', 'BOOK_DELTA', 'TRADES_BATCH', 'CANDLE_HISTORY',
    ]);
    expect(parsed[2].bookVersion).toBe(100);
    expect(parsed[3]).toMatchObject({ fromVersion: 100, bookVersion: 101 });
    expect(parsed[4]).toMatchObject({ fromVersion: 101, bookVersion: 102 });
  });

  it('trims the buffer when a newer snapshot arrives', async () => {
    const pub = await connectPublisher();
    await seedMarket1(pub);
    pub.send(JSON.stringify({ type: 'BOOK_DELTA', marketId: 1, bookVersion: 101, fromVersion: 100, changes: [] }));
    pub.send(bundle({ ...SNAP_1, version: 101, bookVersion: 101, bidVersion: 101 }));
    await sleep(50);

    const viewer = await connect('/ws');
    const frames = take(viewer, 5); // no replayed deltas expected
    viewer.send(JSON.stringify({ action: 'subscribe', marketId: 1 }));
    const parsed = (await frames).map((f) => JSON.parse(f));
    expect(parsed.map((p) => p.type)).toEqual(['SUBSCRIPTION_CONFIRMED', 'TICKER_STATS', 'BOOK_SNAPSHOT', 'TRADES_BATCH', 'CANDLE_HISTORY']);
    expect(parsed[2].bookVersion).toBe(101);
  });
});

describe('viewer actions', () => {
  it('answers ping with PONG', async () => {
    const viewer = await connect('/ws');
    const frame = take(viewer, 1);
    viewer.send(JSON.stringify({ action: 'ping' }));
    expect(JSON.parse((await frame)[0]).type).toBe('PONG');
  });

  it('refresh resends book, trades, candles for the subscribed market', async () => {
    const pub = await connectPublisher();
    await seedMarket1(pub);

    const viewer = await connect('/ws');
    const boot = take(viewer, 5);
    viewer.send(JSON.stringify({ action: 'subscribe', marketId: 1 }));
    await boot;

    const refreshed = take(viewer, 3);
    viewer.send(JSON.stringify({ action: 'refresh', marketId: 1 }));
    const types = (await refreshed).map((f) => JSON.parse(f).type);
    expect(types).toEqual(['BOOK_SNAPSHOT', 'TRADES_BATCH', 'CANDLE_HISTORY']);
  });

  it('re-subscribe switches markets: no further frames from the old market', async () => {
    const pub = await connectPublisher();
    await seedMarket1(pub);

    const viewer = await connect('/ws');
    const boot = take(viewer, 5);
    viewer.send(JSON.stringify({ action: 'subscribe', marketId: 1 }));
    await boot;

    const boot2 = take(viewer, 2); // confirmed + empty snapshot for market 2
    viewer.send(JSON.stringify({ action: 'subscribe', marketId: 2 }));
    await boot2;

    const next = take(viewer, 1);
    pub.send(JSON.stringify({ type: 'BOOK_DELTA', marketId: 1, bookVersion: 101, fromVersion: 100, changes: [] }));
    pub.send(JSON.stringify({ type: 'BOOK_DELTA', marketId: 2, market: 'ETH-USD', bookVersion: 7, fromVersion: 6, changes: [] }));
    const got = JSON.parse((await next)[0]);
    expect(got).toMatchObject({ type: 'BOOK_DELTA', marketId: 2 });
  });

  it('recent trades merge live batches and cap at 50', async () => {
    const pub = await connectPublisher();
    await seedMarket1(pub);
    for (let i = 0; i < 60; i++) {
      pub.send(JSON.stringify({
        type: 'TRADES_BATCH', marketId: 1, market: 'BTC-USD', timestamp: 10 + i,
        trades: [{ price: 100 + i, quantity: 0.1, tradeCount: 1, timestamp: 10 + i, side: 'SELL' }],
      }));
    }
    await sleep(100);

    const viewer = await connect('/ws');
    const frames = take(viewer, 5);
    viewer.send(JSON.stringify({ action: 'subscribe', marketId: 1 }));
    const trades = (await frames).map((f) => JSON.parse(f)).find((p) => p.type === 'TRADES_BATCH');
    expect(trades.trades).toHaveLength(50);
    // Oldest-first: the last entry is the newest trade published.
    expect(trades.trades[49].price).toBe(159);
  });
});

describe('health surface', () => {
  it('exposes stats on /healthz', async () => {
    const pub = await connectPublisher();
    await seedMarket1(pub);
    const res = await SELF.fetch('https://relay.test/healthz');
    expect(res.status).toBe(200);
    const body = (await res.json()) as { publishers: number; markets: Record<string, { market: string }> };
    expect(body.publishers).toBeGreaterThanOrEqual(1);
    expect(body.markets['1'].market).toBe('BTC-USD');
  });
});
