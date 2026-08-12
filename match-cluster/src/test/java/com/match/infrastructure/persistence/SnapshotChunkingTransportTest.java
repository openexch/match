// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.orderbook.MatchingEngine;
import com.match.domain.FixedPoint;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.IdleStrategy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A-2 regression: a cluster snapshot LARGER than the snapshot channel's {@code maxMessageLength}
 * must survive a full transport round-trip. Before the fix, {@code onTakeSnapshot} offered the whole
 * snapshot as ONE Aeron message, so the moment the payload exceeded {@code maxMessageLength}
 * (snapshot term / 8) the offer threw {@link IllegalArgumentException} — the snapshot failed
 * permanently, the log was never pruned, and tmpfs filled. The fix CHUNKS the payload into
 * {@code maxMessageLength}-sized messages on the way out and ACCUMULATES the reassembled messages on
 * the way back in; the {@link SnapshotCodec} byte format is unchanged.
 *
 * <p>This drives the REAL production encode path — {@link AppClusteredService#offerSnapshotChunked}
 * — and the SAME {@code FragmentAssembler}+accumulate reassembly {@code loadSnapshot} uses for
 * decode, over a REAL Aeron IPC publication whose term-length (64 KiB → {@code maxMessageLength}
 * 8 KiB) and MTU (1408 B) force BOTH multi-chunk (several messages) AND multi-fragment (several MTU
 * frames per message). A ~1,200-order book serializes to ~38 KiB, five-ish chunks, each spanning
 * multiple MTU fragments. The reassembled payload is asserted byte-identical to the original, then
 * decoded via the unchanged {@link SnapshotCodec} and asserted field-for-field against the source
 * engine (orderIdGenerator, tradeIdGenerator, timerCorrelationId, and both books of every market).
 *
 * <p><b>Why a transport round-trip and not a full embedded-cluster restart?</b> There is no
 * restart/recovery harness in this module — {@code EmbeddedClusterTest} and
 * {@code SnapshotCadenceClusterTest} start a cluster but never stop-and-recover one — and the
 * recovered engine's scalars (orderIdGenerator, tradeIdGenerator, timerCorrelationId) are not
 * observable through egress, so a restart test could not assert them. The A-2 fix is precisely the
 * two transport loops; this test exercises both (including the back-pressure retry branch, via a
 * subscriber-draining idle strategy) against real Aeron framing while asserting every recovered
 * field exactly. {@code SnapshotCadenceClusterTest} continues to cover the single-chunk encode path
 * through the real cluster, and {@code SnapshotCodecTest} covers byte-determinism of the format.
 */
public class SnapshotChunkingTransportTest {

    /** 64 KiB term → maxMessageLength 8 KiB; MTU 1408 B → each chunk spans several MTU fragments. */
    private static final String CHANNEL = "aeron:ipc?term-length=64k|mtu=1408";
    private static final int STREAM_ID = 1300;
    private static final long TRADE_ID_IN = 987_654_321L;
    private static final long TIMER_ID_IN = 555_000_111_222L;
    private static final long ORDER_ID_GEN_IN = 4_242_424_242L;
    private static final int BID_ORDERS = 600;
    private static final int ASK_ORDERS = 600;

    private MediaDriver mediaDriver;
    private Aeron aeron;
    private File aeronDir;

    @Before
    public void setUp() {
        aeronDir = new File(System.getProperty("java.io.tmpdir"),
                "aeron-snap-chunk-" + System.nanoTime());
        mediaDriver = MediaDriver.launchEmbedded(
                new MediaDriver.Context()
                        .aeronDirectoryName(aeronDir.getAbsolutePath())
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true)
                        .threadingMode(ThreadingMode.SHARED));
        aeron = Aeron.connect(new Aeron.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName()));
    }

    @After
    public void tearDown() {
        CloseHelper.closeAll(aeron, mediaDriver);
        if (aeronDir != null && aeronDir.exists()) {
            IoUtil.delete(aeronDir, true);
        }
    }

    @Test
    public void largeSnapshotSurvivesChunkedTransportRoundTrip() {
        // ---- 1. Build a book big enough to exceed maxMessageLength several times over ----
        final Engine orig = new Engine();
        final MatchingEngine btc = orig.getEngine(Engine.MARKET_BTC_USD);
        final MatchingEngine eth = orig.getEngine(Engine.MARKET_ETH_USD);
        long oid = 1;
        for (int i = 0; i < BID_ORDERS; i++, oid++) {
            btc.addOrderNoMatch(oid, 100_000 + oid, true, fp(60_000.0), fp(1.0));   // resting BIDs (FIFO)
        }
        for (int i = 0; i < ASK_ORDERS; i++, oid++) {
            eth.addOrderNoMatch(oid, 100_000 + oid, false, fp(3_000.0), fp(2.0));   // resting ASKs
        }
        // Pin a non-trivial generator so the round-trip proves the scalar, not just 0 == 0.
        orig.setOrderIdGenerator(ORDER_ID_GEN_IN);

        final ExpandableArrayBuffer payload = new ExpandableArrayBuffer();
        final int pos = SnapshotCodec.serialize(orig, TRADE_ID_IN, TIMER_ID_IN, payload);
        final byte[] expectedBytes = Arrays.copyOf(payload.byteArray(), pos);

        // ---- 2. Open a REAL Aeron IPC pub/sub whose small term forces multi-chunk + multi-fragment ----
        final Subscription sub = aeron.addSubscription(CHANNEL, STREAM_ID);
        final ExclusivePublication pub = aeron.addExclusivePublication(CHANNEL, STREAM_ID);
        try {
            awaitConnected(pub, sub);

            final int maxLen = pub.maxMessageLength();
            assertTrue("test precondition: snapshot (" + pos + " B) must exceed maxMessageLength ("
                            + maxLen + " B) so the chunking path is actually exercised",
                    pos > maxLen);

            // ---- 3. Reassemble exactly as loadSnapshot does: FragmentAssembler + accumulate ----
            final ExpandableArrayBuffer accum = new ExpandableArrayBuffer();
            final int[] accumLen = {0};
            final FragmentAssembler assembler = new FragmentAssembler((buf, offset, length, header) -> {
                accum.putBytes(accumLen[0], buf, offset, length);
                accumLen[0] += length;
            });
            // Drain the subscriber whenever the publisher back-pressures (a small term guarantees it
            // will), so the production offer loop makes progress. Single-threaded and deterministic,
            // and it exercises the fix's back-pressure retry branch for real.
            final IdleStrategy drainingIdle = new IdleStrategy() {
                public void idle(final int workCount) {
                    sub.poll(assembler, 64);
                }

                public void idle() {
                    sub.poll(assembler, 64);
                }

                public void reset() {
                }
            };

            // ---- 4. Encode via the REAL production chunker ----
            final int chunks = AppClusteredService.offerSnapshotChunked(pub, payload, pos, drainingIdle);
            assertTrue("payload must be sent as MULTIPLE chunks to exercise the fix (got " + chunks + ")",
                    chunks >= 2);

            // Drain the tail the back-pressure idle never reached.
            final long deadline = System.currentTimeMillis() + 10_000;
            while (accumLen[0] < pos) {
                if (sub.poll(assembler, 64) == 0 && System.currentTimeMillis() > deadline) {
                    break;
                }
            }

            // ---- 5. The reassembled bytes must be the ORIGINAL snapshot, byte for byte ----
            assertEquals("reassembled length must equal serialized length", pos, accumLen[0]);
            assertArrayEquals("chunked + reassembled payload must be byte-identical to the original snapshot",
                    expectedBytes, Arrays.copyOf(accum.byteArray(), accumLen[0]));

            // ---- 6. Decode the reassembled payload and assert FULL state equality ----
            final Engine restored = new Engine();
            final SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(accum, 0, accumLen[0], restored);

            assertEquals("orderId generator", ORDER_ID_GEN_IN, restored.getOrderIdGenerator());
            assertEquals("tradeId generator carried", TRADE_ID_IN, decoded.tradeIdGenerator);
            assertTrue("timer correlation present", decoded.timerCorrelationIdPresent);
            assertEquals("timer correlation carried", TIMER_ID_IN, decoded.timerCorrelationId);
            assertEquals("no resting order dropped across the chunked round-trip", 0, decoded.rejectedOrders);

            final var marketIt = orig.getEngines().keySet().iterator();
            while (marketIt.hasNext()) {
                final int m = marketIt.nextInt();
                assertArrayEquals("bid book mismatch for market " + m,
                        orig.getEngine(m).getBidOrders(), restored.getEngine(m).getBidOrders());
                assertArrayEquals("ask book mismatch for market " + m,
                        orig.getEngine(m).getAskOrders(), restored.getEngine(m).getAskOrders());
            }

            System.out.println("[TEST] snapshot " + pos + " B sent in " + chunks
                    + " chunk(s) of <= " + maxLen + " B, reassembled byte-identical, state restored.");
        } finally {
            CloseHelper.closeAll(sub, pub);
        }
    }

    private static void awaitConnected(final ExclusivePublication pub, final Subscription sub) {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (!(pub.isConnected() && sub.isConnected())) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("IPC pub/sub did not connect within 5s");
            }
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting IPC connection", e);
            }
        }
    }

    private static long fp(final double v) {
        return FixedPoint.fromDouble(v);
    }
}
