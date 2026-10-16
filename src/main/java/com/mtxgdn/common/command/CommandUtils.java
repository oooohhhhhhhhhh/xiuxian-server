package com.mtxgdn.common.command;

public final class CommandUtils {

    private CommandUtils() {}

    public static String formatDuration(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) return hours + "小时" + minutes + "分钟";
        return minutes + "分钟";
    }

    public static String formatDuration(long totalSeconds) {
        return formatDuration((int) totalSeconds);
    }

    public static Long parseLong(String s, Long defaultValue) {
        if (s == null || s.isBlank()) return defaultValue;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Integer parseInt(String s, Integer defaultValue) {
        if (s == null || s.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Double parseDouble(String s, Double defaultValue) {
        if (s == null || s.isBlank()) return defaultValue;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static String[] split(String arg, int limit) {
        if (arg == null || arg.isBlank()) return new String[0];
        return arg.trim().split("\\s+", limit);
    }

    public static String[] split(String arg) {
        return split(arg, 0);
    }

    public static boolean hasEnoughArgs(String[] parts, int minCount) {
        return parts != null && parts.length >= minCount;
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String truncate(String s, int maxLength) {
        if (s == null) return "";
        return s.length() > maxLength ? s.substring(0, maxLength - 3) + "..." : s;
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }
}
