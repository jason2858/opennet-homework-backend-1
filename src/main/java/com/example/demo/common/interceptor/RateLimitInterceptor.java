package com.example.demo.common.interceptor;

import com.example.demo.common.exception.ApiException;
import com.example.demo.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private record Rule(int maxRequests, long windowMs) {}

    // Global limit applied before per-IP checks
    private static final Rule GLOBAL_RULE = new Rule(200, 1_000);
    // Per-path per-IP rules; unmatched paths use DEFAULT_RULE
    private static final Rule DEFAULT_RULE = new Rule(100, 60_000);
    private static final Map<String, Rule> PATH_RULES = Map.of(
        "/api/notifications", new Rule(20, 60_000)
    );

    private final Window globalWindow = new Window();
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!globalWindow.tryAcquire(GLOBAL_RULE.maxRequests(), GLOBAL_RULE.windowMs())) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Service rate limit exceeded");
        }

        String ip = resolveClientIp(req);
        String path = req.getRequestURI();
        Rule rule = PATH_RULES.getOrDefault(path, DEFAULT_RULE);

        Window window = windows.computeIfAbsent(ip + ":" + path, k -> new Window());
        if (!window.tryAcquire(rule.maxRequests(), rule.windowMs())) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many requests from " + ip);
        }
        return true;
    }

    @Scheduled(fixedDelay = 120_000)
    public void evictExpiredWindows() {
        windows.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private String resolveClientIp(HttpServletRequest req) {
        return req.getRemoteAddr();
    }

    private static class Window {
        private long windowStart = System.currentTimeMillis();
        private int count = 0;

        synchronized boolean tryAcquire(int max, long windowMs) {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMs) {
                windowStart = now;
                count = 1;
                return true;
            }
            if (count >= max) return false;
            count++;
            return true;
        }

        synchronized boolean isExpired() {
            // Remove entries idle for more than 10 minutes
            return System.currentTimeMillis() - windowStart > 600_000;
        }
    }
}
