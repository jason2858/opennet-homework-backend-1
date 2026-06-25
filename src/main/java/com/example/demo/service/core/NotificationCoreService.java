package com.example.demo.service.core;

import com.example.demo.common.exception.NotificationNotFoundException;
import com.example.demo.model.entity.Notification;
import com.example.demo.model.enums.NotificationStatus;
import com.example.demo.repository.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationCoreService {

    private static final int DEFAULT_RECENT_LIMIT = 10;

    private final NotificationMapper mapper;

    public Notification findByIdOrThrow(Long id) {
        return mapper.findById(id)
            .orElseThrow(() -> new NotificationNotFoundException(id));
    }

    public void insert(Notification notification) {
        mapper.insert(notification);
    }

    public void update(Notification notification) {
        mapper.update(notification);
    }

    public List<Notification> findRecent() {
        return mapper.findRecentByLimit(DEFAULT_RECENT_LIMIT);
    }

    public List<Notification> findPendingScheduled(LocalDateTime now, int maxRetries) {
        return mapper.findPendingScheduled(NotificationStatus.SCHEDULED.name(), now, maxRetries);
    }

    public List<Notification> findStuckPending(LocalDateTime before) {
        return mapper.findStuckPending(before);
    }
}
