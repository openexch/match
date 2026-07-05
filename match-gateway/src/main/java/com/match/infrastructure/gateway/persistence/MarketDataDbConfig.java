// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Environment-driven configuration for the market-data TimescaleDB connection.
 * Mirrors the OMS config conventions (URL/user/password env vars, password-file
 * variant for secrets). When disabled the gateway runs pure in-memory, exactly
 * as before persistence existed.
 */
public record MarketDataDbConfig(String url, String user, String password, boolean enabled) {

    public static MarketDataDbConfig fromEnv() {
        String url = env("MARKET_PG_URL", "jdbc:postgresql://localhost:5432/marketdata");
        String user = env("MARKET_PG_USER", "market");
        String password = secret("MARKET_PG_PASSWORD");
        boolean enabledFlag = !"false".equalsIgnoreCase(env("MARKET_PG_ENABLED", "true"));
        boolean enabled = enabledFlag && password != null && !password.isEmpty();
        return new MarketDataDbConfig(url, user, password, enabled);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }

    /** Resolve a secret from NAME or NAME_FILE (file contents, trimmed). */
    private static String secret(String name) {
        String direct = System.getenv(name);
        if (direct != null && !direct.isEmpty()) {
            return direct;
        }
        String file = System.getenv(name + "_FILE");
        if (file != null && !file.isEmpty()) {
            try {
                return Files.readString(Path.of(file)).trim();
            } catch (Exception e) {
                System.err.println("[market-pg] failed to read " + name + "_FILE: " + e.getMessage());
            }
        }
        return null;
    }
}
