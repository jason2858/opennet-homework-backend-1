package com.example.demo.util;

import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class NotificationRedisScripts {

    public static final DefaultRedisScript<Long> PUSH_RECENT = new DefaultRedisScript<>("""
            redis.call('LPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], 0, 9)
            return 1
            """, Long.class);

    private NotificationRedisScripts() {}
}
