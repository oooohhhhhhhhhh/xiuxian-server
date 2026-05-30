package com.mtxgdn.util;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class GameLogger {

    public enum Level {
        DEBUG(0, "DEBUG", "\u001B[36m"),
        INFO(1, "INFO", "\u001B[32m"),
        WARN(2, "WARN", "\u001B[33m"),
        ERROR(3, "ERROR", "\u001B[31m");

        final int value;
        final String label;
        final String ansiColor;

        Level(int value, String label, String ansiColor) {
            this.value = value;
            this.label = label;
            this.ansiColor = ansiColor;
        }
    }

    private static final String RESET = "\u001B[0m";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter STARTUP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Map<String, GameLogger> LOGGERS = new ConcurrentHashMap<>();

    private static Level globalLevel = Level.DEBUG;
    private static String logDir = "log";
    private static boolean useAnsiColor = true;

    private static final int MAX_MEMORY_LOG_ENTRIES = 500;
    private static final List<LogEntry> memoryLog = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicLong logSequence = new AtomicLong(0);

    private static PrintWriter sharedFileWriter;
    private static final ReentrantLock sharedFileLock = new ReentrantLock();
    private static String logFilePath;

    public static class LogEntry {
        public final long seq;
        public final String timestamp;
        public final String level;
        public final String loggerName;
        public final String message;
        public final boolean hasThrowable;

        LogEntry(long seq, String timestamp, String level, String loggerName, String message, boolean hasThrowable) {
            this.seq = seq;
            this.timestamp = timestamp;
            this.level = level;
            this.loggerName = loggerName;
            this.message = message;
            this.hasThrowable = hasThrowable;
        }
    }

    public static List<LogEntry> getRecentLogs(long sinceSeq) {
        synchronized (memoryLog) {
            List<LogEntry> result = new ArrayList<>();
            for (LogEntry entry : memoryLog) {
                if (entry.seq > sinceSeq) {
                    result.add(entry);
                }
            }
            return result;
        }
    }

    public static long getLatestLogSeq() {
        return logSequence.get();
    }

    public static String getLogFilePath() {
        return logFilePath;
    }

    static {
        String levelStr = AppConfig.get("logging.level", "DEBUG");
        try {
            globalLevel = Level.valueOf(levelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            globalLevel = Level.DEBUG;
        }

        String logDirCfg = AppConfig.get("logging.dir", "log");
        if (logDirCfg != null && !logDirCfg.isBlank()) {
            logDir = logDirCfg;
        }

        String colorCfg = AppConfig.get("logging.color", "true");
        useAnsiColor = !"false".equalsIgnoreCase(colorCfg);

        initSharedFileWriter();
    }

    private static void initSharedFileWriter() {
        try {
            Path logDirPath = Paths.get(logDir);
            Files.createDirectories(logDirPath);

            String startupTime = LocalDateTime.now().format(STARTUP_FORMATTER);
            String fileName = "server-" + startupTime + ".log";
            Path logFile = logDirPath.resolve(fileName);
            logFilePath = logFile.toAbsolutePath().toString();
            sharedFileWriter = new PrintWriter(new FileWriter(logFile.toFile(), true), true);
        } catch (IOException e) {
            System.err.println("[GameLogger] 无法创建日志文件: " + e.getMessage());
            sharedFileWriter = null;
        }
    }

    private final String name;

    private GameLogger(String name) {
        this.name = name;
    }

    public static GameLogger getLogger(Class<?> clazz) {
        return getLogger(clazz.getSimpleName());
    }

    public static GameLogger getLogger(String name) {
        return LOGGERS.computeIfAbsent(name, GameLogger::new);
    }

    public static void setLevel(Level level) {
        globalLevel = level;
    }

    public static void writeRawLine(String line) {
        sharedFileLock.lock();
        try {
            if (sharedFileWriter != null) {
                sharedFileWriter.println(line);
                sharedFileWriter.flush();
            }
        } finally {
            sharedFileLock.unlock();
        }
    }

    // ---- 实例方法 ----

    public void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    public void info(String message) {
        log(Level.INFO, message, null);
    }

    public void warn(String message) {
        log(Level.WARN, message, null);
    }

    public void error(String message) {
        log(Level.ERROR, message, null);
    }

    public void error(String message, Throwable throwable) {
        log(Level.ERROR, message, throwable);
    }

    public void log(Level level, String message, Throwable throwable) {
        if (level.value < globalLevel.value) {
            return;
        }

        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String threadName = Thread.currentThread().getName();
        String formatted = formatMessage(level, timestamp, threadName, message, throwable);

        boolean hasThrowable = throwable != null;
        long seq = logSequence.incrementAndGet();
        LogEntry entry = new LogEntry(seq, timestamp, level.label, name, message, hasThrowable);
        synchronized (memoryLog) {
            memoryLog.add(entry);
            while (memoryLog.size() > MAX_MEMORY_LOG_ENTRIES) {
                memoryLog.remove(0);
            }
        }

        writeToConsole(level, formatted);
        writeToFile(formatted);
    }

    private String formatMessage(Level level, String timestamp, String threadName, String message, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(timestamp).append("] ");
        sb.append("[").append(level.label).append("] ");
        sb.append("[").append(name).append("] ");
        sb.append("[").append(threadName).append("] ");
        sb.append(message);

        if (throwable != null) {
            sb.append(System.lineSeparator());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            pw.flush();
            sb.append(sw.toString());
        }

        return sb.toString();
    }

    private void writeToConsole(Level level, String formatted) {
        if (useAnsiColor) {
            System.out.println(level.ansiColor + formatted + RESET);
        } else {
            if (level.value >= Level.WARN.value) {
                System.err.println(formatted);
            } else {
                System.out.println(formatted);
            }
        }
    }

    private void writeToFile(String formatted) {
        sharedFileLock.lock();
        try {
            if (sharedFileWriter != null) {
                sharedFileWriter.println(formatted);
                sharedFileWriter.flush();
            }
        } finally {
            sharedFileLock.unlock();
        }
    }

    public static void shutdown() {
        sharedFileLock.lock();
        try {
            if (sharedFileWriter != null) {
                sharedFileWriter.close();
                sharedFileWriter = null;
            }
        } finally {
            sharedFileLock.unlock();
        }
        LOGGERS.clear();
    }
}
