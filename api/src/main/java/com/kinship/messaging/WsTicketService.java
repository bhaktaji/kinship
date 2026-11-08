package com.kinship.messaging;

import com.kinship.common.api.ApiException;
import com.kinship.common.config.AppProperties;
import com.kinship.group.GroupRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class WsTicketService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final GroupRepository groups;
    private final Duration ttl;

    public WsTicketService(StringRedisTemplate redis, GroupRepository groups, AppProperties properties) {
        this.redis = redis;
        this.groups = groups;
        this.ttl = properties.realtime() != null && properties.realtime().ticketTtl() != null
                ? properties.realtime().ticketTtl() : Duration.ofSeconds(60);
    }

    public Issued issue(UUID userId, UUID groupId) {
        GroupRepository.GroupRow group = groups.findGroup(groupId)
                .orElseThrow(() -> ApiException.notFound("NOT_FOUND", "Group not found"));
        if (!"ACTIVE".equals(group.status())) {
            throw ApiException.conflict("GROUP_ARCHIVED", "Group is archived");
        }
        var membership = groups.membership(groupId, userId)
                .orElseThrow(() -> ApiException.forbidden("FORBIDDEN", "Active membership required"));
        if (!"ACTIVE".equals(membership.status())) {
            throw ApiException.forbidden("FORBIDDEN", "Active membership required");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String ticket = BASE64.encodeToString(bytes);
        try {
            redis.opsForValue().set(key(ticket), userId + "|" + groupId, ttl);
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "Realtime store unavailable");
        }
        return new Issued(ticket, ttl.toSeconds());
    }

    public Optional<Ticket> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().getAndDelete(key(ticket));
            if (value == null) {
                return Optional.empty();
            }
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2) {
                return Optional.empty();
            }
            return Optional.of(new Ticket(UUID.fromString(parts[0]), UUID.fromString(parts[1])));
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                    "Realtime store unavailable");
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String keyFor(String ticket) {
        return key(ticket);
    }

    private String key(String ticket) {
        try {
            return "ws_ticket:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Issued(String ticket, long expiresIn) {
    }

    public record Ticket(UUID userId, UUID groupId) {
    }
}
