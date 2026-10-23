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

    private static final int MAX_MEMORY_LOG_ENTRIES = AppConfig.getInt("performance.memory_log_entries", 200);
    private static final List<LogEntry> memoryLog = Collections.synchronizedList(new ArrayList<>(MAX_MEMORY_LOG_ENTRIES + 10));
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
            // 显式 UTF-8：避免中文 Windows 默认 GBK 导致日志乱码
            sharedFileWriter = new PrintWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(logFile.toFile(), true), java.nio.charset.StandardCharsets.UTF_8), true);
            pruneOldLogFiles(logDirPath);
        } catch (IOException e) {
            System.err.println("[GameLogger] 无法创建日志文件: " + e.getMessage());
            sharedFileWriter = null;
        }
    }

    /**
     * 保留最近 {@code logging.max_files} 个日志文件，更旧的删掉。
     *
     * <p><b>为什么需要</b>：日志文件名带启动时间戳（{@code server-yyyyMMdd-HHmmss.log}），
     * 每次启动新建一个，而<b>从来不删旧的</b>。默认日志级别还是 DEBUG，
     * 于是每重启一次就多一个几十 MB 的文件，{@code log/} 目录只增不减 ——
     * 长时间运维的服务器最终会被日志撑满磁盘，而磁盘满又会连带数据库写失败。</p>
     *
     * <p>文件名里的时间戳是 {@code yyyyMMdd-HHmmss}，字典序即时间序，
     * 因此直接按文件名排序删最旧的即可，不需要读文件属性。
     * {@code logging.max_files <= 0} 表示不清理（保留全部）。</p>
     */
    private static void pruneOldLogFiles(Path logDirPath) {
        int keep = AppConfig.getInt("logging.max_files", 10);
        if (keep <= 0) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(logDirPath)) {
            java.util.List<Path> files = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("server-") && n.endsWith(".log");
                    })
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(java.util.stream.Collectors.toList());
            int toDelete = files.size() - keep;
            for (int i = 0; i < toDelete; i++) {
                try {
                    Files.deleteIfExists(files.get(i));
                } catch (IOException ignored) {
                    // 单个文件删不掉（被占用等）不影响启动
                }
            }
        } catch (IOException ignored) {
            // 清理失败不影响日志功能本身
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
