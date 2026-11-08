package com.kinship.group;

import com.kinship.common.api.ApiException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
public class GroupPageCursor {

    public String encode(Instant joinedAt, UUID groupId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((joinedAt.toEpochMilli() + "|" + groupId).getBytes(StandardCharsets.UTF_8));
    }

    public Cursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            if (parts.length != 2) {
                throw new IllegalArgumentException("bad cursor");
            }
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_CURSOR", "Invalid pagination cursor");
        }
    }

    public record Cursor(Instant joinedAt, UUID groupId) {
    }
}
