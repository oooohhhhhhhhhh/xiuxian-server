package com.mtxgdn.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimiter {

    private static final ConcurrentHashMap<String, RateWindow> windows = new ConcurrentHashMap<>();

    private static class RateWindow {
        final AtomicLong counter = new AtomicLong(0);
        final long windowStart;
        volatile int windowSeconds;

        RateWindow(long windowStart, int limit, int windowSeconds) {
            this.windowStart = windowStart;
            this.windowSeconds = windowSeconds;
        }
    }

    public static boolean allow(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis() / 1000;
        RateWindow window = windows.compute(key, (k, old) -> {
            if (old == null || (now - old.windowStart) >= old.windowSeconds) {
                return new RateWindow(now, limit, windowSeconds);
            }
            return old;
        });

        if (now - window.windowStart >= window.windowSeconds) {
            window.counter.set(0);
            windows.put(key, new RateWindow(now, limit, windowSeconds));
            return true;
        }

        return window.counter.incrementAndGet() <= limit;
    }

    public static long getRemaining(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis() / 1000;
        RateWindow window = windows.get(key);
        if (window == null || (now - window.windowStart) >= windowSeconds) {
            return limit;
        }
        return Math.max(0, limit - window.counter.get());
    }
}
