package com.mtxgdn.util;

import com.mtxgdn.db.PlayerActionLogDAO;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PlayerActionLogger {

    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String LOG_DIR = AppConfig.get("logging.dir", "log");
    private static final String PLAYER_LOG_PREFIX = "player_actions";

    private static PlayerActionLogger INSTANCE;

    private boolean consoleEnabled;
    private PrintWriter fileWriter;
    private String currentLogDate;

    private PlayerActionLogger() {
        this.consoleEnabled = true;
        this.currentLogDate = LocalDateTime.now().format(FILE_FORMATTER);
        initFileWriter();
    }

    public static synchronized PlayerActionLogger getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PlayerActionLogger();
        }
        return INSTANCE;
    }

    private void initFileWriter() {
        try {
            Path logDirPath = Paths.get(LOG_DIR);
            Files.createDirectories(logDirPath);
            String fileName = PLAYER_LOG_PREFIX + "-" + currentLogDate + ".log";
            Path logFile = logDirPath.resolve(fileName);
            // 显式 UTF-8：避免中文 Windows 默认 GBK 导致日志乱码
            fileWriter = new PrintWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(logFile.toFile(), true), java.nio.charset.StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            System.err.println("[PlayerActionLogger] 无法创建玩家操作日志文件: " + e.getMessage());
            fileWriter = null;
        }
    }

    private synchronized void rollIfNeeded() {
        String today = LocalDateTime.now().format(FILE_FORMATTER);
        if (!today.equals(currentLogDate)) {
            currentLogDate = today;
            if (fileWriter != null) {
                fileWriter.close();
            }
            initFileWriter();
        }
    }

    // ===== 公开日志方法 =====

    public void logConnect(long userId, String username) {
        log(userId, username, "连接", "玩家上线", null);
    }

    public void logDisconnect(long userId, String username) {
        log(userId, username, "断开", "玩家下线", null);
    }

    public void logChat(long userId, String username, String content) {
        log(userId, username, "聊天", "发言: " + truncate(content, 100), null);
    }

    public void logCreatePlayer(long userId, String playerName) {
        log(userId, playerName, "创建角色", "创建了修仙角色", null);
    }

    public void logCultivateStart(long userId, String playerName, int realm) {
        log(userId, playerName, "开始修炼", "境界: " + realm, null);
    }

    public void logCultivateStop(long userId, String playerName, long expGained, int elapsedSeconds) {
        log(userId, playerName, "停止修炼", "获得经验: " + expGained + ", 修炼时长: " + elapsedSeconds + "秒", null);
    }

    public void logBreakthrough(long userId, String playerName, boolean success, String message) {
        String result = success ? "成功" : "失败";
        log(userId, playerName, "境界突破", result + " - " + message, null);
    }

    public void logExploration(long userId, String playerName, String eventName, String result) {
        log(userId, playerName, "游历探索", "事件: " + eventName + ", 结果: " + result, null);
    }

    public void logSecretRealmEnter(long userId, String playerName, String areaName, boolean success, String message) {
        String status = success ? "成功" : "失败";
        log(userId, playerName, "秘境探索", "区域: " + areaName + ", " + status + " - " + message, null);
    }

    public void logItemUse(long userId, String playerName, String itemKey, boolean success, String message) {
        String status = success ? "使用成功" : "使用失败";
        log(userId, playerName, "使用物品", "物品: " + itemKey + ", " + status + " - " + message, null);
    }

    public void logSkillLearn(long userId, String playerName, String skillName, boolean success, String message) {
        String status = success ? "学习成功" : "学习失败";
        log(userId, playerName, "学习技能", "技能: " + skillName + ", " + status + " - " + message, null);
    }

    public void logCombat(long userId, String playerName, String type, String targetName, boolean win, String detail) {
        String result = win ? "胜利" : "失败";
        log(userId, playerName, "战斗", type + " vs " + targetName + ", " + result + " - " + detail, null);
    }

    public void logItemAdd(long userId, String playerName, String itemKey, int quantity) {
        log(userId, playerName, "获得物品", "物品: " + itemKey + " x" + quantity, null);
    }

    public void logCustom(long userId, String playerName, String action, String detail) {
        log(userId, playerName, action, detail, null);
    }

    // ===== QQ操作日志方法 =====

    public void logCultivateStart(long userId, String playerName, int realm, String qqNumber) {
        log(userId, playerName, "开始修炼", "境界: " + realm, qqNumber);
    }

    public void logCultivateStop(long userId, String playerName, long expGained, int elapsedSeconds, String qqNumber) {
        log(userId, playerName, "停止修炼", "获得经验: " + expGained + ", 修炼时长: " + elapsedSeconds + "秒", qqNumber);
    }

    public void logBreakthrough(long userId, String playerName, boolean success, String message, String qqNumber) {
        String result = success ? "成功" : "失败";
        log(userId, playerName, "境界突破", result + " - " + message, qqNumber);
    }

    public void logExploration(long userId, String playerName, String eventName, String result, String qqNumber) {
        log(userId, playerName, "游历探索", "事件: " + eventName + ", 结果: " + result, qqNumber);
    }

    public void logSecretRealmEnter(long userId, String playerName, String areaName, boolean success, String message, String qqNumber) {
        String status = success ? "成功" : "失败";
        log(userId, playerName, "秘境探索", "区域: " + areaName + ", " + status + " - " + message, qqNumber);
    }

    public void logItemUse(long userId, String playerName, String itemKey, boolean success, String message, String qqNumber) {
        String status = success ? "使用成功" : "使用失败";
        log(userId, playerName, "使用物品", "物品: " + itemKey + ", " + status + " - " + message, qqNumber);
    }

    public void logSkillLearn(long userId, String playerName, String skillName, boolean success, String message, String qqNumber) {
        String status = success ? "学习成功" : "学习失败";
        log(userId, playerName, "学习技能", "技能: " + skillName + ", " + status + " - " + message, qqNumber);
    }

    public void logCombat(long userId, String playerName, String type, String targetName, boolean win, String detail, String qqNumber) {
        String result = win ? "胜利" : "失败";
        log(userId, playerName, "战斗", type + " vs " + targetName + ", " + result + " - " + detail, qqNumber);
    }

    public void logItemAdd(long userId, String playerName, String itemKey, int quantity, String qqNumber) {
        log(userId, playerName, "获得物品", "物品: " + itemKey + " x" + quantity, qqNumber);
    }

    public void logCustom(long userId, String playerName, String action, String detail, String qqNumber) {
        log(userId, playerName, action, detail, qqNumber);
    }

    // ===== 内部实现 =====

    private void log(long userId, String playerName, String action, String detail, String qqNumber) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String qqPart = qqNumber != null && !qqNumber.isEmpty() ? "[QQ:" + qqNumber + "] " : "";
        String line = String.format("[%s] [玩家行为] %s[UID:%d] [%s] %s | %s",
                timestamp, qqPart, userId, playerName, action, detail);

        // 控制台输出
        if (consoleEnabled) {
            System.out.println(ANSI_CYAN + line + ANSI_RESET);
        }

        // 写入独立日志文件
        rollIfNeeded();
        if (fileWriter != null) {
            fileWriter.println(line);
            fileWriter.flush();
        }

        // 写入数据库
        try {
            new PlayerActionLogDAO().insertPlayerActionLog(userId, playerName, action, detail, qqNumber);
        } catch (Exception e) {
            System.err.println("[PlayerActionLogger] 写入数据库失败: " + e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public void setConsoleEnabled(boolean enabled) {
        this.consoleEnabled = enabled;
    }

    public static void shutdown() {
        if (INSTANCE != null && INSTANCE.fileWriter != null) {
            INSTANCE.fileWriter.close();
        }
        INSTANCE = null;
    }
}
