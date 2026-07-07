// SPDX-License-Identifier: Apache-2.0
package com.match.determinism;

import com.match.application.engine.Engine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.commands.UpdateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.infrastructure.persistence.SnapshotCodec;
import org.agrona.ExpandableArrayBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses and executes a {@code .scenario} file against a real {@link Engine}, capturing the
 * deterministic output stream via {@link RecordingEventSink} and rendering it to canonical text.
 *
 * <p>Order IDs are engine-assigned sequentially from 1, so {@code CANCEL}/{@code UPDATE} refer to
 * orders by that sequence number. Timestamps come from {@link LogicalClock} (never wall-clock).
 * The {@code SNAPSHOT} verb performs a real {@link SnapshotCodec} round-trip into a fresh engine —
 * an in-scenario "restart" — proving snapshot/restore is invisible to subsequent matching.</p>
 *
 * <h3>DSL</h3>
 * <pre>
 *   # comment                              (also: trailing  # ... is stripped)
 *   MARKET BTC_USD                         set current market (default BTC_USD)
 *   CLOCK 1000                             set absolute logical timestamp
 *   CREATE u=100 side=BID type=LIMIT px=60000.0 qty=1.0 [budget=..] [oms=..]
 *   CANCEL u=100 order=1
 *   UPDATE u=100 order=1 side=BID px=61000.0 qty=2.0 [type=LIMIT|LIMIT_MAKER]  (default LIMIT)
 *   SNAPSHOT                               serialize → restore into a fresh engine
 * </pre>
 */
public final class ScenarioRunner {

    private Engine engine;
    private final RecordingEventSink sink = new RecordingEventSink();
    private final LogicalClock clock = new LogicalClock();
    private int market = Engine.MARKET_BTC_USD;
    private long timerCorrelationId = 0L;
    // Matching implementation to use; null = the flag-resolved default (production behavior).
    private final String impl;

    private ScenarioRunner(String impl) {
        this.impl = impl;
        this.engine = newEngine();
        engine.setEventPublisher(sink);
    }

    private Engine newEngine() {
        return impl == null ? new Engine() : new Engine(impl);
    }

    /** Run a scenario file and return its canonical rendered output (flag-resolved impl). */
    public static String run(Path scenarioFile) throws IOException {
        return run(scenarioFile, null);
    }

    /** Run a scenario file through an explicit matching implementation ("array" | "direct"). */
    public static String run(Path scenarioFile, String impl) throws IOException {
        return runLines(Files.readAllLines(scenarioFile, StandardCharsets.UTF_8), impl);
    }

    /** Run a scenario given as raw lines and return its canonical rendered output (flag-resolved impl). */
    public static String runLines(List<String> lines) {
        return runLines(lines, null);
    }

    /** Run a scenario given as raw lines through an explicit matching implementation. */
    public static String runLines(List<String> lines, String impl) {
        ScenarioRunner runner = new ScenarioRunner(impl);
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                runner.exec(line);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(
                        "scenario error at line " + lineNo + ": '" + raw + "' — " + ex.getMessage(), ex);
            }
        }
        return runner.sink.render();
    }

    private void exec(String line) {
        String[] tokens = line.split("\\s+");
        String verb = tokens[0].toUpperCase();
        Map<String, String> kv = parseKv(tokens);

        switch (verb) {
            case "MARKET":
                market = marketId(tokens[1]);
                break;
            case "CLOCK":
                clock.set(Long.parseLong(tokens[1]));
                break;
            case "CREATE":
                engine.acceptOrder(market, Engine.CMD_CREATE, buildCreate(kv), clock.now());
                break;
            case "CANCEL":
                engine.acceptOrder(market, Engine.CMD_CANCEL, buildCancel(kv), clock.now());
                break;
            case "UPDATE":
                engine.acceptOrder(market, Engine.CMD_UPDATE, buildUpdate(kv), clock.now());
                break;
            case "SNAPSHOT":
                snapshotRoundTrip();
                break;
            default:
                throw new IllegalArgumentException("unknown verb '" + verb + "'");
        }
    }

    private CreateOrderCommand buildCreate(Map<String, String> kv) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(reqLong(kv, "u"));
        OrderSide side = side(req(kv, "side"));
        cmd.setOrderSide(side);
        OrderType type = type(kv.getOrDefault("type", "LIMIT"));
        cmd.setOrderType(type);

        if (type == OrderType.MARKET) {
            if (side == OrderSide.BID) {
                // Market buy: spend a budget (totalPrice), no price/qty.
                cmd.setPrice(0);
                cmd.setQuantity(0);
                cmd.setTotalPrice(FixedPoint.fromDouble(reqDouble(kv, "budget")));
            } else {
                // Market sell: a quantity, no price/budget.
                cmd.setPrice(0);
                cmd.setQuantity(FixedPoint.fromDouble(reqDouble(kv, "qty")));
                cmd.setTotalPrice(0);
            }
        } else {
            // LIMIT / LIMIT_MAKER
            cmd.setPrice(FixedPoint.fromDouble(reqDouble(kv, "px")));
            cmd.setQuantity(FixedPoint.fromDouble(reqDouble(kv, "qty")));
            cmd.setTotalPrice(0);
        }

        if (kv.containsKey("oms")) {
            cmd.setOmsOrderId(Long.parseLong(kv.get("oms")));
        }
        return cmd;
    }

    private CancelOrderCommand buildCancel(Map<String, String> kv) {
        CancelOrderCommand cmd = new CancelOrderCommand();
        cmd.setUserId(reqLong(kv, "u"));
        cmd.setOrderId(reqLong(kv, "order"));
        return cmd;
    }

    private UpdateOrderCommand buildUpdate(Map<String, String> kv) {
        UpdateOrderCommand cmd = new UpdateOrderCommand();
        cmd.setUserId(reqLong(kv, "u"));
        cmd.setOrderId(reqLong(kv, "order"));
        cmd.setOrderSide(side(req(kv, "side")));
        // Optional order type on the amend, matching CREATE's type= (default LIMIT = prior behavior).
        // LIMIT_MAKER routes the replacement through the post-only no-cross path (match#92).
        cmd.setOrderType(type(kv.getOrDefault("type", "LIMIT")));
        cmd.setPrice(FixedPoint.fromDouble(reqDouble(kv, "px")));
        cmd.setQuantity(FixedPoint.fromDouble(reqDouble(kv, "qty")));
        return cmd;
    }

    /**
     * Real snapshot round-trip: serialize current engine state, restore into a FRESH engine, and
     * swap it in — simulating a node restart mid-scenario. The sink (and its trade-id counter)
     * persists, exactly as production's event publisher persists while engine state is rebuilt.
     */
    private void snapshotRoundTrip() {
        ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();
        int length = SnapshotCodec.serialize(engine, sink.getTradeIdGenerator(), timerCorrelationId, buffer);

        Engine fresh = newEngine();
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(buffer, 0, length, fresh);
        fresh.setEventPublisher(sink);
        sink.setTradeIdGenerator(decoded.tradeIdGenerator);
        if (decoded.timerCorrelationIdPresent) {
            timerCorrelationId = decoded.timerCorrelationId;
        }
        engine = fresh;
    }

    // ---- parsing helpers ----

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static Map<String, String> parseKv(String[] tokens) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (int i = 1; i < tokens.length; i++) {
            int eq = tokens[i].indexOf('=');
            if (eq > 0) {
                kv.put(tokens[i].substring(0, eq), tokens[i].substring(eq + 1));
            }
        }
        return kv;
    }

    private static String req(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing required field '" + key + "'");
        }
        return v;
    }

    private static long reqLong(Map<String, String> kv, String key) {
        return Long.parseLong(req(kv, key));
    }

    private static double reqDouble(Map<String, String> kv, String key) {
        return Double.parseDouble(req(kv, key));
    }

    private static OrderSide side(String s) {
        switch (s.toUpperCase()) {
            case "BID": case "BUY":  return OrderSide.BID;
            case "ASK": case "SELL": return OrderSide.ASK;
            default: throw new IllegalArgumentException("bad side '" + s + "'");
        }
    }

    private static OrderType type(String s) {
        switch (s.toUpperCase()) {
            case "LIMIT":        return OrderType.LIMIT;
            case "MARKET":       return OrderType.MARKET;
            case "LIMIT_MAKER":  return OrderType.LIMIT_MAKER;
            default: throw new IllegalArgumentException("bad type '" + s + "'");
        }
    }

    private static int marketId(String symbol) {
        switch (symbol.toUpperCase()) {
            case "BTC_USD":  return Engine.MARKET_BTC_USD;
            case "ETH_USD":  return Engine.MARKET_ETH_USD;
            case "SOL_USD":  return Engine.MARKET_SOL_USD;
            case "XRP_USD":  return Engine.MARKET_XRP_USD;
            case "DOGE_USD": return Engine.MARKET_DOGE_USD;
            default: throw new IllegalArgumentException("unknown market '" + symbol + "'");
        }
    }
}
