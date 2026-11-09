// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.websocket;

import com.match.application.engine.Engine;
import com.match.application.publisher.MarketDataBroadcaster;
import com.match.infrastructure.generated.BookDeltaDecoder;
import com.match.infrastructure.generated.BookSnapshotDecoder;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import org.agrona.concurrent.UnsafeBuffer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Test scope: the market publishers of an {@link
 * com.match.infrastructure.persistence.AppClusteredService} that was not started. The service creates its
 * publishers in onStart (with their 20 ms scheduler threads); this rig creates one {@link MarketPublisher} per
 * market, never calls {@link MarketPublisher#onStart()}, wires the engine's book and the service's own
 * broadcaster, and runs {@link MarketPublisher#flushBuffers()} on demand, so "within two leader flushes" is
 * counted in calls, never in wall time.
 */
public final class PublisherRig {

    private final List<MarketPublisher> publishers = new ArrayList<>();

    public PublisherRig(final Engine engine, final MarketDataBroadcaster broadcaster, final int... marketIds) {
        for (int m : marketIds) {
            if (engine.getEngine(m) == null) {
                throw new IllegalStateException("market " + m + " has no engine");
            }
            final MarketPublisher p = new MarketPublisher(m, "M" + m, null);
            p.setMatchingEngine(engine.getEngine(m));
            p.setBroadcaster(broadcaster);
            NegativeControlSeams.apply(p);
            publishers.add(p);
        }
    }

    /** The service's own (private) market-data broadcaster, the one its publishers get in onStart. */
    public static MarketDataBroadcaster broadcasterOf(final Object service) {
        try {
            final Field f = service.getClass().getDeclaredField("aeronBroadcaster");
            f.setAccessible(true);
            return (MarketDataBroadcaster) f.get(service);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** One publisher interval on every market (the scheduler's tick). */
    public void flushAll() {
        for (MarketPublisher p : publishers) {
            p.flushBuffers();
        }
    }

    /** A decoded BookSnapshot (22) or BookDelta (25). */
    public static final class BookFrame {
        public final int templateId;
        public final int marketId;
        public final long bookVersion;
        public final long fromVersion; // -1 for a snapshot
        public final int bids;          // snapshot: bid levels; delta: changes
        public final int asks;          // snapshot: ask levels; delta: 0
        public final List<Integer> updateTypes = new ArrayList<>(); // delta: raw update type per change

        BookFrame(final int templateId, final int marketId, final long bookVersion, final long fromVersion,
                  final int bids, final int asks) {
            this.templateId = templateId;
            this.marketId = marketId;
            this.bookVersion = bookVersion;
            this.fromVersion = fromVersion;
            this.bids = bids;
            this.asks = asks;
        }

        public boolean isSnapshot() {
            return templateId == BookSnapshotDecoder.TEMPLATE_ID;
        }

        @Override
        public String toString() {
            return (isSnapshot() ? "BookSnapshot" : "BookDelta") + "{market=" + marketId + " bookVersion=" + bookVersion
                    + (isSnapshot() ? " bids=" + bids + " asks=" + asks : " from=" + fromVersion + " changes=" + bids)
                    + "}";
        }
    }

    /** The book frames among {@code frames}, in order; every other template is skipped. */
    public static List<BookFrame> bookFrames(final List<byte[]> frames) {
        final List<BookFrame> out = new ArrayList<>();
        final MessageHeaderDecoder h = new MessageHeaderDecoder();
        for (byte[] frame : frames) {
            final UnsafeBuffer b = new UnsafeBuffer(frame);
            h.wrap(b, 0);
            if (h.templateId() == BookSnapshotDecoder.TEMPLATE_ID) {
                final BookSnapshotDecoder d = new BookSnapshotDecoder();
                d.wrap(b, h.encodedLength(), h.blockLength(), h.version());
                final int market = d.marketId();
                final long version = d.bookVersion();
                int bids = 0;
                for (BookSnapshotDecoder.BidsDecoder ignored : d.bids()) {
                    bids++; // a group is read in order: walk the bids before the asks
                }
                int asks = 0;
                for (BookSnapshotDecoder.AsksDecoder ignored : d.asks()) {
                    asks++;
                }
                out.add(new BookFrame(BookSnapshotDecoder.TEMPLATE_ID, market, version, -1, bids, asks));
            } else if (h.templateId() == BookDeltaDecoder.TEMPLATE_ID) {
                final BookDeltaDecoder d = new BookDeltaDecoder();
                d.wrap(b, h.encodedLength(), h.blockLength(), h.version());
                final int market = d.marketId();
                final long version = d.bookVersion();
                final long from = d.fromVersion();
                final List<Integer> types = new ArrayList<>();
                for (BookDeltaDecoder.ChangesDecoder c : d.changes()) {
                    types.add((int) c.updateTypeRaw());
                }
                final BookFrame f = new BookFrame(BookDeltaDecoder.TEMPLATE_ID, market, version, from, types.size(), 0);
                f.updateTypes.addAll(types);
                out.add(f);
            }
        }
        return out;
    }

    /** The book frames of one market. */
    public static List<BookFrame> bookFrames(final List<byte[]> frames, final int marketId) {
        final List<BookFrame> out = new ArrayList<>();
        for (BookFrame f : bookFrames(frames)) {
            if (f.marketId == marketId) {
                out.add(f);
            }
        }
        return out;
    }
}
