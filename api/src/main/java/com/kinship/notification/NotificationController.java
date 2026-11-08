package com.kinship.notification;

import com.kinship.common.api.ApiException;
import com.kinship.common.security.UserPrincipal;
import com.kinship.notification.NotificationService.NotificationPage;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public NotificationPage list(@AuthenticationPrincipal UserPrincipal principal,
                                 @RequestParam(required = false) String pageToken,
                                 @RequestParam(defaultValue = "20") int limit) {
        Instant before = pageToken == null ? null : Instant.parse(pageToken);
        return service.list(require(principal).id(), before, limit);
    }

    @GetMapping("/unread-count")
    public java.util.Map<String, Integer> unread(@AuthenticationPrincipal UserPrincipal principal) {
        return java.util.Map.of("unread_count", service.list(require(principal).id(), null, 1).unreadCount());
    }

    @PatchMapping("/{id}/read")
    public void read(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        service.markRead(require(principal).id(), id);
    }

    @PostMapping("/read-all")
    public void readAll(@AuthenticationPrincipal UserPrincipal principal) {
        service.markAllRead(require(principal).id());
    }

    private UserPrincipal require(UserPrincipal principal) {
        if (principal == null) throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication required");
        return principal;
    }
}