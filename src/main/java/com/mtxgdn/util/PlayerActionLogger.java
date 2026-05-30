package com.mtxgdn.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PlayerActionLogger {

    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static PlayerActionLogger INSTANCE;

    private boolean consoleEnabled;

    private PlayerActionLogger() {
        this.consoleEnabled = true;
    }

    public static synchronized PlayerActionLogger getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PlayerActionLogger();
        }
        return INSTANCE;
    }

    public void logConnect(long userId, String username) {
        log(userId, username, "连接", "玩家上线");
    }

    public void logDisconnect(long userId, String username) {
        log(userId, username, "断开", "玩家下线");
    }

    public void logChat(long userId, String username, String content) {
        log(userId, username, "聊天", "发言: " + truncate(content, 100));
    }

    public void logCreatePlayer(long userId, String playerName) {
        log(userId, playerName, "创建角色", "创建了修仙角色");
    }

    public void logCultivateStart(long userId, String playerName, int realm) {
        log(userId, playerName, "开始修炼", "境界: " + realm);
    }

    public void logCultivateStop(long userId, String playerName, long expGained, int elapsedSeconds) {
        log(userId, playerName, "停止修炼", "获得经验: " + expGained + ", 修炼时长: " + elapsedSeconds + "秒");
    }

    public void logBreakthrough(long userId, String playerName, boolean success, String message) {
        String result = success ? "成功" : "失败";
        log(userId, playerName, "境界突破", result + " - " + message);
    }

    public void logExploration(long userId, String playerName, String eventName, String result) {
        log(userId, playerName, "游历探索", "事件: " + eventName + ", 结果: " + result);
    }

    public void logSecretRealmEnter(long userId, String playerName, String areaName, boolean success, String message) {
        String status = success ? "成功" : "失败";
        log(userId, playerName, "秘境探索", "区域: " + areaName + ", " + status + " - " + message);
    }

    public void logItemUse(long userId, String playerName, String itemKey, boolean success, String message) {
        String status = success ? "使用成功" : "使用失败";
        log(userId, playerName, "使用物品", "物品: " + itemKey + ", " + status + " - " + message);
    }

    public void logSkillLearn(long userId, String playerName, String skillName, boolean success, String message) {
        String status = success ? "学习成功" : "学习失败";
        log(userId, playerName, "学习技能", "技能: " + skillName + ", " + status + " - " + message);
    }

    public void logCombat(long userId, String playerName, String type, String targetName, boolean win, String detail) {
        String result = win ? "胜利" : "失败";
        log(userId, playerName, "战斗", type + " vs " + targetName + ", " + result + " - " + detail);
    }

    public void logItemAdd(long userId, String playerName, String itemKey, int quantity) {
        log(userId, playerName, "获得物品", "物品: " + itemKey + " x" + quantity);
    }

    public void logCustom(long userId, String playerName, String action, String detail) {
        log(userId, playerName, action, detail);
    }

    private void log(long userId, String playerName, String action, String detail) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String line = String.format("[%s] [玩家行为] [UID:%d] [%s] %s | %s",
                timestamp, userId, playerName, action, detail);

        if (consoleEnabled) {
            System.out.println(ANSI_CYAN + line + ANSI_RESET);
        }

        GameLogger.writeRawLine(line);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    public void setConsoleEnabled(boolean enabled) {
        this.consoleEnabled = enabled;
    }

    public static void shutdown() {
        INSTANCE = null;
    }
}
