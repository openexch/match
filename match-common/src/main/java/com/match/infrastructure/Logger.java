package com.match.infrastructure;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final String className;

    public enum Level {
        DEBUG(0), INFO(1), WARN(2), ERROR(3), OFF(4);

        final int priority;
        Level(int priority) { this.priority = priority; }
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

    public void info(String message) {
        log(Level.INFO, message);
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
            String timestamp = LocalDateTime.now().format(formatter);
            System.out.printf("%s [%s] %s - %s%n", timestamp, level, className, message);
        }
    }
}
