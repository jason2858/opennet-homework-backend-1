package com.example.demo.repository;

import com.example.demo.common.typehandler.JsonListTypeHandler;
import com.example.demo.constants.MetricsConstants;
import com.example.demo.model.entity.Notification;
import io.micrometer.core.annotation.Timed;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
@Timed(value = MetricsConstants.DB_EXECUTE, percentiles = {0.99, 0.95})
public interface NotificationMapper {

    @Insert("INSERT INTO notifications " +
            "(type, recipient, subject, content, status, from_address, reply_to, sender_id, " +
            " cc, bcc, content_type, attachments, retry_count, last_error, scheduled_at, idempotency_key) " +
            "VALUES " +
            "(#{type}, #{recipient}, #{subject}, #{content}, #{status}, #{fromAddress}, #{replyTo}, #{senderId}, " +
            " #{cc}, #{bcc}, #{contentType}, #{attachments}, #{retryCount}, #{lastError}, #{scheduledAt}, #{idempotencyKey})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Notification notification);

    @Update("UPDATE notifications SET " +
            "subject = #{subject}, content = #{content}, status = #{status}, " +
            "from_address = #{fromAddress}, reply_to = #{replyTo}, sender_id = #{senderId}, " +
            "cc = #{cc}, bcc = #{bcc}, content_type = #{contentType}, attachments = #{attachments}, " +
            "retry_count = #{retryCount}, last_error = #{lastError}, " +
            "scheduled_at = #{scheduledAt}, sent_at = #{sentAt}, " +
            "delivered_at = #{deliveredAt}, read_at = #{readAt}, " +
            "deleted_at = #{deletedAt}, idempotency_key = #{idempotencyKey} " +
            "WHERE id = #{id} AND deleted_at IS NULL")
    int update(Notification notification);

    @Select("SELECT * FROM notifications WHERE id = #{id} AND deleted_at IS NULL")
    @Results(id = "notificationMap", value = {
        @Result(property = "cc", column = "cc", typeHandler = JsonListTypeHandler.class),
        @Result(property = "bcc", column = "bcc", typeHandler = JsonListTypeHandler.class),
        @Result(property = "attachments", column = "attachments", typeHandler = JsonListTypeHandler.class)
    })
    Optional<Notification> findById(Long id);

    @Select("SELECT * FROM notifications WHERE deleted_at IS NULL ORDER BY created_at DESC LIMIT #{limit}")
    @ResultMap("notificationMap")
    List<Notification> findRecentByLimit(@Param("limit") int limit);

    @Select("SELECT * FROM notifications WHERE status = #{status} " +
            "AND scheduled_at <= #{now} AND retry_count < #{maxRetries} AND deleted_at IS NULL")
    @ResultMap("notificationMap")
    List<Notification> findPendingScheduled(@Param("status") String status,
                                            @Param("now") LocalDateTime now,
                                            @Param("maxRetries") int maxRetries);

    @Select("SELECT * FROM notifications WHERE status = 'PENDING' " +
            "AND created_at < #{before} AND deleted_at IS NULL")
    @ResultMap("notificationMap")
    List<Notification> findStuckPending(@Param("before") LocalDateTime before);
}
