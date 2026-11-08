package com.kinship.auth;

import com.kinship.common.api.ApiException;
import com.kinship.common.config.AppProperties;
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
import java.util.Set;
import java.util.UUID;

/**
 * Opaque refresh tokens: rt_&lt;userId&gt;_&lt;tokenId&gt;_&lt;secret&gt;.
 * Only the SHA-256 of the secret is stored. Rotation is single-use; presenting a
 * rotated token revokes the whole token family (reuse detection).
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RefreshTokenService(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.ttl = properties.auth() != null && properties.auth().refreshTtl() != null
                ? properties.auth().refreshTtl() : Duration.ofDays(30);
    }

    public String issue(UUID userId) {
        try {
            return doIssue(userId);
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    private String doIssue(UUID userId) {
        String tokenId = UUID.randomUUID().toString();
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = BASE64.encodeToString(secretBytes);
        String token = "rt_" + userId + "_" + tokenId + "_" + secret;
        redis.opsForValue().set(activeKey(userId, tokenId), sha256(secret), ttl);
        redis.opsForSet().add(indexKey(userId), tokenId);
        redis.expire(indexKey(userId), ttl);
        return token;
    }

    public Rotation rotate(String token) {
        try {
            return doRotate(token);
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    private Rotation doRotate(String token) {
        Parts parts = parse(token);
        // Single-use is enforced by GETDEL: two concurrent refreshes of the same
        // token cannot both read the stored hash before it is removed, so only one
        // of them can succeed (the loser is treated as reuse).
        String stored = redis.opsForValue().getAndDelete(activeKey(parts.userId(), parts.tokenId()));
        if (stored == null) {
            if (Boolean.TRUE.equals(redis.hasKey(usedKey(parts.tokenId())))) {
                revokeAll(parts.userId());
            }
            throw ApiException.unauthorized("INVALID_REFRESH", "Refresh token is invalid or expired");
        }
        if (!MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                sha256(parts.secret()).getBytes(StandardCharsets.UTF_8))) {
            // A valid token id with the wrong secret means the value was forged;
            // drop the whole family rather than leaving the session usable.
            revokeAll(parts.userId());
            throw ApiException.unauthorized("INVALID_REFRESH", "Refresh token is invalid or expired");
        }
        redis.opsForSet().remove(indexKey(parts.userId()), parts.tokenId());
        redis.opsForValue().set(usedKey(parts.tokenId()), parts.userId().toString(), ttl);
        return new Rotation(parts.userId(), issue(parts.userId()));
    }

    public void revoke(String token) {
        try {
            Parts parts = parse(token);
            redis.delete(activeKey(parts.userId(), parts.tokenId()));
            redis.opsForSet().remove(indexKey(parts.userId()), parts.tokenId());
        } catch (ApiException ignored) {
            // Logout is idempotent.
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    public Duration ttl() {
        return ttl;
    }

    private void revokeAll(UUID userId) {
        Set<String> tokenIds = redis.opsForSet().members(indexKey(userId));
        if (tokenIds != null) {
            tokenIds.forEach(tokenId -> redis.delete(activeKey(userId, tokenId)));
        }
        redis.delete(indexKey(userId));
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "Session store unavailable");
    }

    private Parts parse(String token) {
        if (token == null) {
            throw ApiException.unauthorized("INVALID_REFRESH", "Refresh token is invalid or expired");
        }
        String[] parts = token.split("_", 4);
        if (parts.length != 4 || !"rt".equals(parts[0])) {
            throw ApiException.unauthorized("INVALID_REFRESH", "Refresh token is invalid or expired");
        }
        try {
            return new Parts(UUID.fromString(parts[1]), parts[2], parts[3]);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("INVALID_REFRESH", "Refresh token is invalid or expired");
        }
    }

    private String activeKey(UUID userId, String tokenId) {
        return "rt:" + userId + ":" + tokenId;
    }

    private String indexKey(UUID userId) {
        return "rt_index:" + userId;
    }

    private String usedKey(String tokenId) {
        return "rt_used:" + tokenId;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Rotation(UUID userId, String newToken) {
    }

    private record Parts(UUID userId, String tokenId, String secret) {
    }
}
