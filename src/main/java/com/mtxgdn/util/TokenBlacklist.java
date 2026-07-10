package com.mtxgdn.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenBlacklist {

    private static final Map<String, Long> blacklist = new ConcurrentHashMap<>();

    private TokenBlacklist() {
    }

    public static void invalidate(String token) {
        blacklist.put(token, System.currentTimeMillis());
    }

    public static boolean isBlacklisted(String token) {
        return blacklist.containsKey(token);
    }

    public static void cleanupExpired(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        blacklist.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    public static void clear() {
        blacklist.clear();
    }

    public static int size() {
        return blacklist.size();
    }
}