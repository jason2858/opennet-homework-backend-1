package com.example.demo.common.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);
    private static final String START_KEY = "requestStart";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        req.setAttribute(START_KEY, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, Exception ex) {
        Object startAttr = req.getAttribute(START_KEY);
        if (startAttr == null) return;
        long elapsed = System.currentTimeMillis() - (long) startAttr;
        if (ex != null) {
            log.warn("{} {} {} {}ms [{}]",
                req.getMethod(), req.getRequestURI(), res.getStatus(), elapsed, ex.getMessage());
        } else {
            log.info("{} {} {} {}ms", req.getMethod(), req.getRequestURI(), res.getStatus(), elapsed);
        }
    }
}
