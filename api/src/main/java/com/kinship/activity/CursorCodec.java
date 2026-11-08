package com.kinship.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.common.api.ApiException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Component
public class CursorCodec {

    private final ObjectMapper objectMapper;

    public CursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(String fingerprint, long distanceM, UUID activityId) {
        try {
            Cursor cursor = new Cursor(1, fingerprint, distanceM, activityId);
            byte[] json = objectMapper.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode cursor", e);
        }
    }

    public Cursor decode(String token, String expectedFingerprint) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(token);
            Cursor cursor = objectMapper.readValue(json, Cursor.class);
            if (cursor.v() != 1 || cursor.activityId() == null || cursor.fingerprint() == null
                    || !cursor.fingerprint().equals(expectedFingerprint)) {
                throw invalid();
            }
            return cursor;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    public String fingerprint(double lat, double lng, double radiusKm, String category,
                              java.time.Instant startsAfter, Integer groupSizeMax) {
        String raw = String.format(Locale.ROOT, "%.5f|%.5f|%.1f|%s|%s|%s",
                lat, lng, radiusKm,
                category == null ? "" : category,
                startsAfter == null ? "" : startsAfter.toString(),
                groupSizeMax == null ? "" : groupSizeMax);
        return sha256(raw);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ApiException invalid() {
        return ApiException.badRequest("INVALID_CURSOR", "Invalid pagination cursor");
    }

    public record Cursor(int v, String fingerprint, long distanceM, UUID activityId) {
    }
}
