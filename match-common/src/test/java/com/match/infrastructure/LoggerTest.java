// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for Logger: level filtering, formatting, factory method.
 */
public class LoggerTest {

    private Logger logger;
    private Logger.Level originalLevel;

    @Before
    public void setUp() {
        originalLevel = Logger.getLevel();
        logger = Logger.getLogger(LoggerTest.class);
    }

    @After
    public void tearDown() {
        // Restore original level (static state)
        Logger.setLevel(originalLevel);
    }

    // ==================== Factory ====================

    @Test
    public void testGetLogger_ReturnsNonNull() {
        Logger log = Logger.getLogger(String.class);
        assertNotNull(log);
    }

    @Test
    public void testGetLogger_DifferentClasses_ReturnDifferentLoggers() {
        Logger log1 = Logger.getLogger(String.class);
        Logger log2 = Logger.getLogger(Integer.class);
        assertNotSame(log1, log2);
    }

    // ==================== Level Get/Set ====================

    @Test
    public void testSetLevel_AndGetLevel() {
        Logger.setLevel(Logger.Level.DEBUG);
        assertEquals(Logger.Level.DEBUG, Logger.getLevel());

        Logger.setLevel(Logger.Level.WARN);
        assertEquals(Logger.Level.WARN, Logger.getLevel());
    }

    // ==================== Level Enum Values ====================

    @Test
    public void testLevel_AllValuesExist() {
        Logger.Level[] values = Logger.Level.values();
        assertEquals(5, values.length);
        assertNotNull(Logger.Level.valueOf("DEBUG"));
        assertNotNull(Logger.Level.valueOf("INFO"));
        assertNotNull(Logger.Level.valueOf("WARN"));
        assertNotNull(Logger.Level.valueOf("ERROR"));
        assertNotNull(Logger.Level.valueOf("OFF"));
    }

    @Test
    public void testLevel_PriorityOrder() {
        assertTrue(Logger.Level.DEBUG.priority < Logger.Level.INFO.priority);
        assertTrue(Logger.Level.INFO.priority < Logger.Level.WARN.priority);
        assertTrue(Logger.Level.WARN.priority < Logger.Level.ERROR.priority);
        assertTrue(Logger.Level.ERROR.priority < Logger.Level.OFF.priority);
    }

    // ==================== Logging at Various Levels (no exceptions) ====================

    @Test
    public void testInfo_SimpleMessage_NoException() {
        Logger.setLevel(Logger.Level.DEBUG);
        logger.info("test info message");
        // No exception = pass
    }

    @Test
    public void testInfo_FormatMessage_NoException() {
        Logger.setLevel(Logger.Level.DEBUG);
        logger.info("value: %d, name: %s", 42, "test");
    }

    @Test
    public void testWarn_SimpleMessage_NoException() {
        Logger.setLevel(Logger.Level.DEBUG);
        logger.warn("test warn message");
    }

    @Test
    public void testWarn_FormatMessage_NoException() {
        Logger.setLevel(Logger.Level.DEBUG);
        logger.warn("warning: %s at %d", "something", 123);
    }

    @Test
    public void testError_SimpleMessage_NoException() {
        Logger.setLevel(Logger.Level.DEBUG);
        logger.error("test error message");
    }

    @Test
    public void testError_FormatMessage_NoException() {
        Logger.setLevel(Logger.Level.DEBUG);
        logger.error("error code: %d, msg: %s", 500, "fail");
    }

    @Test
    public void testDebug_SimpleMessage_NoException() {
        Logger.setLevel(Logger.Level.DEBUG);
        logger.debug("test debug message");
    }

    @Test
    public void testDebug_FormatMessage_NoException() {
        Logger.setLevel(Logger.Level.DEBUG);
        logger.debug("debug val: %s", "xyz");
    }

    // ==================== Level Filtering ====================

    @Test
    public void testLevelFiltering_WarnLevel_InfoFormatSkipped() {
        Logger.setLevel(Logger.Level.WARN);
        // info(format, args) should NOT format if level is WARN
        // This should not throw even if args don't match format
        // (because the formatting is guarded by level check)
        logger.info("This %s should not be %s formatted", "message");
        // If it tried to format, it would fail because only 1 arg for 2 %s
        // No exception = pass (format was skipped)
    }

    @Test
    public void testLevelFiltering_WarnLevel_WarnFormatExecuted() {
        Logger.setLevel(Logger.Level.WARN);
        // warn should still format at WARN level
        logger.warn("warn: %s", "executed");
    }

    @Test
    public void testLevelFiltering_WarnLevel_ErrorFormatExecuted() {
        Logger.setLevel(Logger.Level.WARN);
        logger.error("error: %s", "executed");
    }

    @Test
    public void testLevelFiltering_ErrorLevel_WarnFormatSkipped() {
        Logger.setLevel(Logger.Level.ERROR);
        // warn format should be skipped at ERROR level
        logger.warn("This %s should not be %s formatted", "message");
    }

    @Test
    public void testLevelFiltering_OffLevel_NothingLogged() {
        Logger.setLevel(Logger.Level.OFF);
        logger.debug("skip %s", "this");
        logger.info("skip %s", "this");
        logger.warn("skip %s", "this");
        logger.error("skip %s", "this");
        // No exceptions
    }

    @Test
    public void testLevelFiltering_DebugLevel_AllFormatExecuted() {
        Logger.setLevel(Logger.Level.DEBUG);
        logger.debug("debug: %d", 1);
        logger.info("info: %d", 2);
        logger.warn("warn: %d", 3);
        logger.error("error: %d", 4);
    }

    @Test
    public void testLevelFiltering_DebugFormatSkipped_AtInfoLevel() {
        Logger.setLevel(Logger.Level.INFO);
        // debug format should be skipped at INFO level
        logger.debug("This %s should not be %s formatted", "message");
    }

    // ==================== Line format ====================

    private static String capture(Runnable r) {
        java.io.PrintStream saved = System.out;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(bytes, true));
        try {
            r.run();
        } finally {
            System.setOut(saved);
        }
        return bytes.toString();
    }

    /** ISO-8601 UTC with milliseconds at the line start, the level padded to one width, the text unchanged. */
    @Test
    public void testLine_StartsWithIsoUtcTimeAndPaddedLevel() {
        Logger.setLevel(Logger.Level.DEBUG);
        String out = capture(() -> {
            logger.info("an info line");
            logger.warn("a warn line %d", 7);
            logger.error("an error line");
            logger.debug("a debug line");
        });
        String[] lines = out.split(System.lineSeparator());
        assertEquals(4, lines.length);
        String time = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";
        assertTrue(lines[0], lines[0].matches(time + " \\[INFO \\] LoggerTest - an info line"));
        assertTrue(lines[1], lines[1].matches(time + " \\[WARN \\] LoggerTest - a warn line 7"));
        assertTrue(lines[2], lines[2].matches(time + " \\[ERROR\\] LoggerTest - an error line"));
        assertTrue(lines[3], lines[3].matches(time + " \\[DEBUG\\] LoggerTest - a debug line"));
        long written = java.time.Instant.parse(lines[0].substring(0, 24)).toEpochMilli();
        assertTrue("the time is now, in UTC", Math.abs(written - System.currentTimeMillis()) < 5_000);
    }

    /** A status line is INFO and is printed at the default level, where info lines are not. */
    @Test
    public void testStatus_PrintedAsInfoAtTheDefaultLevel() {
        Logger.setLevel(Logger.Level.ERROR);
        String out = capture(() -> {
            logger.info("filtered");
            logger.status("quorum watchdog armed");
        });
        assertFalse(out, out.contains("filtered"));
        assertTrue(out, out.contains(" [INFO ] LoggerTest - quorum watchdog armed"));
        assertFalse(out, out.contains("[ERROR]"));
        Logger.setLevel(Logger.Level.OFF);
        assertEquals("", capture(() -> logger.status("nothing at OFF")));
    }
}
