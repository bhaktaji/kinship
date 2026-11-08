package com.kinship.image;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ImageRepository {
    private final JdbcClient jdbc;

    public ImageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID ownerUserId, String imageType, UUID groupId, UUID reviewId, String storageKey,
                       String contentType, long sizeBytes) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO image_assets (id, owner_user_id, image_type, group_id, review_id, storage_key, content_type, size_bytes)
                        VALUES (:id, :owner, :type, :groupId, :reviewId, :key, :contentType, :size)
                        """)
                .param("id", id).param("owner", ownerUserId).param("type", imageType)
                .param("groupId", groupId).param("reviewId", reviewId).param("key", storageKey)
                .param("contentType", contentType).param("size", sizeBytes).update();
        return id;
    }

    public Optional<ImageRecord> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, owner_user_id, image_type, group_id, review_id, storage_key, content_type, size_bytes, created_at
                          FROM image_assets WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new ImageRecord(rs.getObject("id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                        rs.getString("image_type"), rs.getObject("group_id", UUID.class),
                        rs.getObject("review_id", UUID.class), rs.getString("storage_key"),
                        rs.getString("content_type"), rs.getLong("size_bytes"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM image_assets WHERE id = :id").param("id", id).update();
    }

    public record ImageRecord(UUID id, UUID ownerUserId, String imageType, UUID groupId, UUID reviewId, String storageKey,
                              String contentType, long sizeBytes, Instant createdAt) {
    }
}