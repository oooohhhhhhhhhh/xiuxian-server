package com.mtxgdn.util;

public class TextSanitizer {

    public static String sanitizeContent(String input) {
        if (input == null) return "";

        StringBuilder sb = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                case '&': sb.append("&amp;"); break;
                default: sb.append(c);
            }
        }

        return sb.toString();
    }

    public static String sanitizePlayerName(String name) {
        if (name == null || name.trim().isEmpty()) return "";
        String sanitized = name.trim();
        sanitized = sanitized.replaceAll("[<>\"'&\\n\\r\\t]", "");
        if (sanitized.length() > 16) {
            sanitized = sanitized.substring(0, 16);
        }
        if (sanitized.isEmpty()) return "";
        return sanitized;
    }

    public static String sanitizeChatContent(String content) {
        if (content == null) return "";
        String sanitized = sanitizeContent(content);
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500);
        }
        return sanitized;
    }
}
