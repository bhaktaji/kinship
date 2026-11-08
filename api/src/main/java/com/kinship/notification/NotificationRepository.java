package com.kinship.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationRepository {
    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID userId, String type, String title, String body, String entityType, UUID entityId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO notifications (id, user_id, type, title, body, entity_type, entity_id)
                        VALUES (:id, :userId, :type, :title, :body, :entityType, :entityId)
                        """)
                .param("id", id).param("userId", userId).param("type", type)
                .param("title", title).param("body", body).param("entityType", entityType)
                .param("entityId", entityId).update();
        return id;
    }

    public List<NotificationRecord> list(UUID userId, Instant before, int limit) {
        return jdbc.sql("""
                        SELECT id, user_id, type, title, body, entity_type, entity_id, read_at, created_at
                          FROM notifications
                         WHERE user_id = :userId
                           AND (CAST(:before AS timestamptz) IS NULL OR created_at < CAST(:before AS timestamptz))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("userId", userId)
                .param("before", before == null ? null : Timestamp.from(before))
                .param("limit", limit)
                .query((rs, n) -> new NotificationRecord(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("type"), rs.getString("title"), rs.getString("body"),
                        rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public int unreadCount(UUID userId) {
        Integer c = jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :userId AND read_at IS NULL")
                .param("userId", userId).query(Integer.class).single();
        return c == null ? 0 : c;
    }

    public void markRead(UUID userId, UUID id) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE id = :id AND user_id = :userId")
                .param("id", id).param("userId", userId).update();
    }

    public void markAllRead(UUID userId) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :userId AND read_at IS NULL")
                .param("userId", userId).update();
    }

    public record NotificationRecord(UUID id, UUID userId, String type, String title, String body,
                                    String entityType, UUID entityId, Instant readAt, Instant createdAt) {
    }
}