// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import org.junit.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The no-silent-fallback contract: persistence is either configured or EXPLICITLY
 * declared off — an absent password refuses startup instead of quietly running
 * in-memory (which is a chart wipe scheduled for the next restart).
 */
public class MarketDataDbConfigTest {

    private static UnaryOperator<String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    public void configuredPasswordEnablesPersistence() {
        MarketDataDbConfig cfg = MarketDataDbConfig.fromEnv(env(Map.of(
                "MARKET_PG_PASSWORD", "hunter2")));
        assertTrue(cfg.enabled());
        assertEquals("hunter2", cfg.password());
        assertEquals("jdbc:postgresql://localhost:5432/marketdata", cfg.url());
        assertEquals("market", cfg.user());
    }

    @Test
    public void explicitDisableIsTheOnlyWayToRunWithoutPersistence() {
        MarketDataDbConfig cfg = MarketDataDbConfig.fromEnv(env(Map.of(
                "MARKET_PG_ENABLED", "false")));
        assertFalse(cfg.enabled());
        assertNull(cfg.password());
    }

    @Test
    public void missingPasswordRefusesStartupLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MarketDataDbConfig.fromEnv(env(Map.of())));
        assertTrue("the refusal must say how to fix it: " + e.getMessage(),
                e.getMessage().contains("MARKET_PG_PASSWORD"));
        assertTrue("and how to declare persistence off on purpose: " + e.getMessage(),
                e.getMessage().contains("MARKET_PG_ENABLED=false"));
    }

    @Test
    public void emptyPasswordIsMissingNotConfigured() {
        assertThrows(IllegalStateException.class,
                () -> MarketDataDbConfig.fromEnv(env(Map.of("MARKET_PG_PASSWORD", ""))));
    }

    @Test
    public void unreadablePasswordFileIsAConfigurationErrorNotAnAbsence() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MarketDataDbConfig.fromEnv(env(Map.of(
                        "MARKET_PG_PASSWORD_FILE", "/nonexistent/market-pg-password"))));
        assertTrue(e.getMessage().contains("MARKET_PG_PASSWORD_FILE"));
    }
}
