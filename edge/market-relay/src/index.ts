export { MarketFeed } from './feed';
import type { MarketFeed } from './feed';

export interface Env {
  FEED: DurableObjectNamespace<MarketFeed>;
  /** wrangler secret; the gateway presents it as a bearer header on /publish. */
  PUBLISH_TOKEN?: string;
}

/** All markets flow through one instance; splitting per market is a naming
 *  change here if a single object ever becomes the bottleneck. */
const FEED_INSTANCE = 'feed';

/** Pin the object near the publisher (the origin box is in Europe): every
 *  frame enters the edge there, so DO distance from the box is pure added
 *  latency for everyone. Hints only apply on (re)creation — existing
 *  objects stay put — this makes the good case deterministic. */
const FEED_LOCATION: DurableObjectLocationHint = 'weur';

function publishTokenOk(header: string | null, expected: string | undefined): boolean {
  if (!expected || !header?.startsWith('Bearer ')) return false;
  const enc = new TextEncoder();
  const got = enc.encode(header.slice(7));
  const want = enc.encode(expected);
  if (got.byteLength !== want.byteLength) return false;
  return crypto.subtle.timingSafeEqual(got, want);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const feed = () => env.FEED.get(env.FEED.idFromName(FEED_INSTANCE), { locationHint: FEED_LOCATION });

    switch (url.pathname) {
      case '/ws':
        return feed().fetch(request);
      case '/publish':
        if (!publishTokenOk(request.headers.get('Authorization'), env.PUBLISH_TOKEN)) {
          return new Response('Unauthorized', { status: 401 });
        }
        return feed().fetch(request);
      case '/healthz':
        return Response.json(await feed().stats());
      default:
        return new Response('Not found', { status: 404 });
    }
  },
} satisfies ExportedHandler<Env>;
