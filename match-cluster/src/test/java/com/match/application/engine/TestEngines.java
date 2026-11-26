// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

/**
 * Test-only factory for an {@link Engine} built at construction from the compiled market table
 * ({@code MarketConfig.ALL_MARKETS}). Production engines are created only from the replicated
 * EngineConfig ({@link Engine#deferredUntilConfig()}); the constructors this factory reaches are
 * package-private, so no production class outside the engine package can build one.
 */
public final class TestEngines {

    private TestEngines() {
    }

    /** Engines for every compiled market, implementation from {@code match.engine.impl} (default array). */
    public static Engine withCompiledMarkets() {
        return new Engine();
    }

    /** Engines for every compiled market with an explicit implementation ("array" | "direct"). */
    public static Engine withCompiledMarkets(final String impl) {
        return new Engine(impl);
    }
}
