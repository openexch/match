// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The node's logger. One line per message, to stdout:
 * {@code 2026-11-27T11:44:31.654Z [INFO ] ClassName - message}. The time is ISO-8601 in UTC with milliseconds,
 * the format every other line of a member's log starts with, so a log viewer orders all of them by one clock.
 */
public class Logger {
    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private final String className;

    public enum Level {
        DEBUG(0), INFO(1), WARN(2), ERROR(3), OFF(4);

        final int priority;
        /** The level in a line, padded to one width: {@code [INFO ]}, {@code [ERROR]}. */
        final String tag;
        Level(int priority) {
            this.priority = priority;
            this.tag = "[" + String.format("%-5s", name()) + "] ";
        }
    }

    // Default to ERROR level - only critical logs
    private static Level currentLevel = Level.ERROR;

    public Logger(Class<?> clazz) {
        this.className = clazz.getSimpleName();
    }

    public static Logger getLogger(Class<?> clazz) {
        return new Logger(clazz);
    }

    public static void setLevel(Level level) {
        currentLevel = level;
    }

    public static Level getLevel() {
        return currentLevel;
    }

    /**
     * True if WARN-level messages will be emitted. Guard a hot-path warn(...)
     * call with this before the argument list is built: the varargs Object[]
     * and primitive boxing are allocated at the call site BEFORE warn()'s own
     * level check, so at the default ERROR level they allocate for nothing.
     */
    public boolean isWarn() {
        return currentLevel.priority <= Level.WARN.priority;
    }

    public void info(String message) {
        log(Level.INFO, message);
    }

    /**
     * An INFO line that is printed at every level but OFF: a line an operator reads once per start (which watchdog
     * is armed, with which bounds), which is neither an error nor noise to be filtered with the other INFO lines.
     */
    public void status(String message) {
        if (currentLevel != Level.OFF) {
            print(Level.INFO, message);
        }
    }

    public void info(String format, Object... args) {
        if (currentLevel.priority <= Level.INFO.priority) {
            log(Level.INFO, String.format(format, args));
        }
    }

    public void warn(String message) {
        log(Level.WARN, message);
    }

    public void warn(String format, Object... args) {
        if (currentLevel.priority <= Level.WARN.priority) {
            log(Level.WARN, String.format(format, args));
        }
    }

    public void error(String message) {
        log(Level.ERROR, message);
    }

    public void error(String format, Object... args) {
        if (currentLevel.priority <= Level.ERROR.priority) {
            log(Level.ERROR, String.format(format, args));
        }
    }

    public void debug(String message) {
        log(Level.DEBUG, message);
    }

    public void debug(String format, Object... args) {
        if (currentLevel.priority <= Level.DEBUG.priority) {
            log(Level.DEBUG, String.format(format, args));
        }
    }

    private void log(Level level, String message) {
        if (currentLevel.priority <= level.priority) {
            print(level, message);
        }
    }

    /** One println of the whole line, so a line is never written in parts. */
    private void print(Level level, String message) {
        System.out.println(formatter.format(Instant.now()) + " " + level.tag + className + " - " + message);
    }
}
