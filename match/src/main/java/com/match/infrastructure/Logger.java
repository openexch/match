package com.match.infrastructure;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final String className;
    
    public Logger(Class<?> clazz) {
        this.className = clazz.getSimpleName();
    }
    
    public static Logger getLogger(Class<?> clazz) {
        return new Logger(clazz);
    }
    
    public void info(String message) {
        log("INFO", message);
    }
    
    public void info(String format, Object... args) {
        log("INFO", String.format(format, args));
    }
    
    public void warn(String message) {
        log("WARN", message);
    }
    
    public void warn(String format, Object... args) {
        log("WARN", String.format(format, args));
    }
    
    public void error(String message) {
        log("ERROR", message);
    }
    
    public void error(String format, Object... args) {
        log("ERROR", String.format(format, args));
    }
    
    public void debug(String message) {
        log("DEBUG", message);
    }
    
    public void debug(String format, Object... args) {
        log("DEBUG", String.format(format, args));
    }
    
    private void log(String level, String message) {
        String timestamp = LocalDateTime.now().format(formatter);
        System.out.printf("%s [%s] %s - %s%n", timestamp, level, className, message);
    }
}