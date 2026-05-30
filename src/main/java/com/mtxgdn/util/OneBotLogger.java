package com.mtxgdn.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class OneBotLogger {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final GameLogger systemLog = GameLogger.getLogger("OneBot");

    private static volatile OneBotLogger instance;
    private static final Object initLock = new Object();

    private OneBotLogger() {
    }

    public static OneBotLogger getInstance() {
        if (instance == null) {
            synchronized (initLock) {
                if (instance == null) {
                    instance = new OneBotLogger();
                }
            }
        }
        return instance;
    }

    public void logRecv(String rawJson) {
        writeLog("RECV", "system", rawJson);
    }

    public void logRecv(String qqAccount, String rawJson) {
        writeLog("RECV", qqAccount, rawJson);
    }

    public void logSend(String targetQq, String rawJson) {
        writeLog("SEND", targetQq, rawJson);
    }

    public void logSendToGroup(Long groupId, String rawJson) {
        writeLog("SEND", "group:" + groupId, rawJson);
    }

    public void logSystem(String message) {
        systemLog.info(message);
    }

    private void writeLog(String direction, String qqTag, String rawJson) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String compactJson = rawJson.replaceAll("\\s+", " ");
        String line = "[" + timestamp + "] [" + direction + "] [QQ:" + qqTag + "] " + compactJson;
        GameLogger.writeRawLine(line);
    }
}
