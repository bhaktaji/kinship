package com.kinship.notification;

import com.kinship.common.api.ApiException;
import com.kinship.common.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {
    private final NotificationRepository repo;

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void notify(UUID userId, String type, String title, String body, String entityType, UUID entityId) {
        if (userId == null) return;
        repo.insert(userId, type, title, body, entityType, entityId);
    }

    @Transactional(readOnly = true)
    public NotificationPage list(UUID userId, Instant before, int limit) {
        List<NotificationRepository.NotificationRecord> rows = repo.list(userId, before, limit + 1);
        boolean more = rows.size() > limit;
        List<NotificationRepository.NotificationRecord> selected = more ? rows.subList(0, limit) : rows;
        String next = more && !selected.isEmpty() ? selected.get(selected.size() - 1).createdAt().toString() : null;
        return new NotificationPage(selected.stream().map(this::toDto).toList(), repo.unreadCount(userId), next);
    }

    @Transactional
    public void markRead(UUID userId, UUID id) {
        repo.markRead(userId, id);
    }

    @Transactional
    public void markAllRead(UUID userId) {
        repo.markAllRead(userId);
    }

    private NotificationDto toDto(NotificationRepository.NotificationRecord r) {
        return new NotificationDto(r.id(), r.type(), r.title(), r.body(), r.entityType(), r.entityId(), r.readAt(), r.createdAt());
    }

    public record NotificationDto(UUID id, String type, String title, String body, String entityType, UUID entityId, Instant readAt, Instant createdAt) {
    }

    public record NotificationPage(List<NotificationDto> items, int unreadCount, String nextPageToken) {
    }
}