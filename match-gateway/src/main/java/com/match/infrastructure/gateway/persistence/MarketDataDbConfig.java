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
 * <p><b>There is no fallback and no off switch.</b> The gateway requires its
 * database: a missing password is a refused startup, not a quiet downgrade to
 * in-memory candles. The prior outage this rule comes from: the password was
 * never provisioned, the gateway "ran fine" for weeks on memory alone, and the
 * first restart silently discarded every chart.</p>
 */
public record MarketDataDbConfig(String url, String user, String password) {

    public static MarketDataDbConfig fromEnv() {
        return fromEnv(System::getenv);
    }

    /** Testable variant: {@code env} stands in for {@link System#getenv(String)}. */
    static MarketDataDbConfig fromEnv(UnaryOperator<String> env) {
        String url = env(env, "MARKET_PG_URL", "jdbc:postgresql://localhost:5432/marketdata");
        String user = env(env, "MARKET_PG_USER", "market");
        String password = secret(env, "MARKET_PG_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException(
                    "market-data persistence is not configured: set MARKET_PG_PASSWORD or"
                            + " MARKET_PG_PASSWORD_FILE. The market gateway does not run without its"
                            + " database — there is no in-memory mode; charts must survive restarts.");
        }
        return new MarketDataDbConfig(url, user, password);
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
