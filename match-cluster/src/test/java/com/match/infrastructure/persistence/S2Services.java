// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** RC2 S2 test support: an {@link AppClusteredService} in a chosen launch mode with a recording fail-fast. */
final class S2Services {

    private S2Services() {
    }

    static final class Service {
        final AppClusteredService service;
        final AtomicInteger failFast = new AtomicInteger();

        Service(boolean configMode) {
            final String prior = System.getProperty("match.engine.from.config");
            System.setProperty("match.engine.from.config", Boolean.toString(configMode));
            try {
                service = new AppClusteredService();
            } finally {
                if (prior == null) {
                    System.clearProperty("match.engine.from.config");
                } else {
                    System.setProperty("match.engine.from.config", prior);
                }
            }
            service.failFast = failFast::incrementAndGet;
            if (engine().isConfigMode() != configMode) {
                throw new IllegalStateException("launch mode not applied (MATCH_ENGINE_FROM_CONFIG set in env?)");
            }
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
    }
}
