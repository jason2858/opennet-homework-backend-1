package com.example.demo.constants;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeConstants {

    public static final ZoneId ZONE = ZoneId.of("UTC");
    public static final DateTimeFormatter RATE_LIMIT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private TimeConstants() {}
}
