// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** Test support: an {@link AppClusteredService} with or without compiled-market engines and recording fail-fast seams. */
final class LaunchModeServices {

    private LaunchModeServices() {
    }

    /**
     * A hand-built v9 body (through the omsMap count) as the current writer frames it: the empty v1
     * ledger block, then envelope 1 with its length and CRC32C.
     */
    static byte[] envelopeV1(final byte[] body) {
        final org.agrona.ExpandableArrayBuffer b = new org.agrona.ExpandableArrayBuffer();
        int p = 20;
        b.putBytes(p, body);
        p += body.length;
        b.putByte(p, (byte) 2);
        b.putInt(p + 1, 1);
        b.putInt(p + 5, 0);
        p += 9;
        final byte[] payload = java.util.Arrays.copyOfRange(b.byteArray(), 20, p);
        final java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(payload, 0, payload.length);
        b.putLong(0, SnapshotCodec.DURABLE_MAGIC);
        b.putInt(8, SnapshotCodec.ENVELOPE_V1);
        b.putInt(12, payload.length);
        b.putInt(16, (int) crc.getValue());
        return java.util.Arrays.copyOf(b.byteArray(), p);
    }

    /** Run {@code r} with System.err captured; return what was printed. */
    static String stderrOf(final Runnable r) {
        final java.io.PrintStream prior = System.err;
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(out, true));
        try {
            r.run();
        } finally {
            System.setErr(prior);
        }
        return out.toString();
    }

    static final class Service {
        final AppClusteredService service;
        final AtomicInteger failFast = new AtomicInteger();

        /** Counts the exit-78 seam alone; {@link #failFast} counts both seams. */
        final AtomicInteger failFastIncompatible = new AtomicInteger();

        /**
         * @param configMode true: the production service (no engines until the first EngineConfig);
         *                   false: a service around a test-factory engine with the compiled markets
         *                   (the removed legacy launch mode, reachable only from tests)
         */
        Service(boolean configMode) {
            service = configMode ? new AppClusteredService()
                    : AppClusteredService.withEngine(com.match.application.engine.TestEngines.withCompiledMarkets());
            service.failFast = failFast::incrementAndGet;
            service.failFastIncompatible = () -> {
                failFastIncompatible.incrementAndGet();
                failFast.incrementAndGet();
            };
        }

        Engine engine() {
            try {
                final Field f = AppClusteredService.class.getDeclaredField("engine");
                f.setAccessible(true);
                return (Engine) f.get(service);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        void restore(byte[] image) {
            service.processSnapshotPayload(new org.agrona.concurrent.UnsafeBuffer(image), 0, image.length);
        }

        /**
         * The onStart load path (reassembly, decode, the CRITICAL refusal and failFast); true when
         * the node would serve, false when it refused the image.
         */
        boolean load(byte[] image) {
            try {
                service.loadSnapshot(new EpochHelloNode.OneShotSource(image), org.agrona.concurrent.NoOpIdleStrategy.INSTANCE);
                return true;
            } catch (final IllegalStateException refused) {
                return false;
            }
        }
    }
}
