// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * Environment-driven configuration for the market-data TimescaleDB connection.
 * Mirrors the OMS config conventions (URL/user/password env vars, password-file
 * variant for secrets).
 *
 * <p><b>There is no silent fallback.</b> Persistence is either configured, or
 * EXPLICITLY declared off with {@code MARKET_PG_ENABLED=false} — a missing
 * password is a refused startup, not a quiet downgrade to in-memory candles.
 * The one prior outage this rule comes from: the password was never
 * provisioned, the gateway "ran fine" for weeks on memory alone, and the first
 * restart silently discarded every chart. A loud refusal at boot would have
 * surfaced that on day one.</p>
 */
public record MarketDataDbConfig(String url, String user, String password, boolean enabled) {

    public static MarketDataDbConfig fromEnv() {
        return fromEnv(System::getenv);
    }

    /** Testable variant: {@code env} stands in for {@link System#getenv(String)}. */
    static MarketDataDbConfig fromEnv(UnaryOperator<String> env) {
        String url = env(env, "MARKET_PG_URL", "jdbc:postgresql://localhost:5432/marketdata");
        String user = env(env, "MARKET_PG_USER", "market");
        boolean explicitlyDisabled = "false".equalsIgnoreCase(env(env, "MARKET_PG_ENABLED", "true"));
        if (explicitlyDisabled) {
            return new MarketDataDbConfig(url, user, null, false);
        }
        String password = secret(env, "MARKET_PG_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException(
                    "market-data persistence is not configured: set MARKET_PG_PASSWORD (or"
                            + " MARKET_PG_PASSWORD_FILE), or declare MARKET_PG_ENABLED=false to run"
                            + " WITHOUT persistence (in-memory only: candles and tickers will not"
                            + " survive a restart). Refusing to start on a silent default.");
        }
        return new MarketDataDbConfig(url, user, password, true);
    }

    private static String env(UnaryOperator<String> env, String name, String defaultValue) {
        String value = env.apply(name);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    /**
     * Resolve a secret from NAME or NAME_FILE (file contents, trimmed). An unreadable
     * NAME_FILE is a configuration error and throws — a secret that was pointed at and
     * cannot be read must never quietly count as "not configured".
     */
    private static String secret(UnaryOperator<String> env, String name) {
        String direct = env.apply(name);
        if (direct != null && !direct.isEmpty()) {
            return direct;
        }
        String file = env.apply(name + "_FILE");
        if (file != null && !file.isEmpty()) {
            try {
                return Files.readString(Path.of(file)).trim();
            } catch (Exception e) {
                throw new IllegalStateException(
                        "failed to read " + name + "_FILE=" + file + ": " + e.getMessage(), e);
            }
        }
        return null;
    }
}
