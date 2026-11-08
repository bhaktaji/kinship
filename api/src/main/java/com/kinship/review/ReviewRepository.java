package com.kinship.review;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReviewRepository {
    private final JdbcClient jdbc;

    public ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID occurrenceId, UUID groupId, UUID userId, int overall, int atmosphere, int organization,
                       String feedback, String vibeTagsJson, Instant editedUntil) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO reviews (id, occurrence_id, group_id, user_id, overall_rating, atmosphere_rating,
                                             organization_rating, feedback, vibe_tags, edited_until)
                        VALUES (:id, :occ, :groupId, :userId, :overall, :atmosphere, :org, :feedback,
                                CAST(:tags AS jsonb), :editedUntil)
                        """)
                .param("id", id).param("occ", occurrenceId).param("groupId", groupId).param("userId", userId)
                .param("overall", overall).param("atmosphere", atmosphere).param("org", organization)
                .param("feedback", feedback).param("tags", vibeTagsJson).param("editedUntil", Timestamp.from(editedUntil))
                .update();
        return id;
    }

    public Optional<ReviewRecord> findByOccurrenceAndUser(UUID occurrenceId, UUID userId) {
        return jdbc.sql("""
                        SELECT id, occurrence_id, group_id, user_id, overall_rating, atmosphere_rating,
                               organization_rating, feedback, vibe_tags::text AS tags, created_at, updated_at, edited_until
                          FROM reviews WHERE occurrence_id = :occ AND user_id = :userId
                        """)
                .param("occ", occurrenceId).param("userId", userId)
                .query(this::map).optional();
    }

    public List<ReviewRecord> listByGroup(UUID groupId, int limit) {
        return jdbc.sql("""
                        SELECT id, occurrence_id, group_id, user_id, overall_rating, atmosphere_rating,
                               organization_rating, feedback, vibe_tags::text AS tags, created_at, updated_at, edited_until
                          FROM reviews WHERE group_id = :groupId ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("groupId", groupId).param("limit", limit)
                .query(this::map).list();
    }

    public void update(UUID id, int overall, int atmosphere, int organization, String feedback, String vibeTagsJson) {
        jdbc.sql("""
                        UPDATE reviews
                           SET overall_rating = :overall, atmosphere_rating = :atmosphere,
                               organization_rating = :org, feedback = :feedback,
                               vibe_tags = CAST(:tags AS jsonb), updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id).param("overall", overall).param("atmosphere", atmosphere)
                .param("org", organization).param("feedback", feedback).param("tags", vibeTagsJson).update();
    }

    public ReviewSummary summaryByGroup(UUID groupId) {
        return jdbc.sql("""
                        SELECT count(*) AS cnt,
                               coalesce(avg(overall_rating),0) AS avg_overall,
                               coalesce(avg(atmosphere_rating),0) AS avg_atm,
                               coalesce(avg(organization_rating),0) AS avg_org
                          FROM reviews WHERE group_id = :groupId
                        """)
                .param("groupId", groupId)
                .query((rs, n) -> new ReviewSummary(rs.getInt("cnt"), rs.getDouble("avg_overall"),
                        rs.getDouble("avg_atm"), rs.getDouble("avg_org")))
                .single();
    }

    public void report(UUID reviewId, UUID reporterId, String reason) {
        jdbc.sql("""
                        INSERT INTO review_reports (id, review_id, reporter_user_id, reason)
                        VALUES (:id, :reviewId, :reporterId, :reason)
                        ON CONFLICT (review_id, reporter_user_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("reviewId", reviewId)
                .param("reporterId", reporterId).param("reason", reason).update();
    }

    private ReviewRecord map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ReviewRecord(rs.getObject("id", UUID.class), rs.getObject("occurrence_id", UUID.class),
                rs.getObject("group_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getInt("overall_rating"), rs.getInt("atmosphere_rating"), rs.getInt("organization_rating"),
                rs.getString("feedback"), rs.getString("tags"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("edited_until").toInstant());
    }

    public record ReviewRecord(UUID id, UUID occurrenceId, UUID groupId, UUID userId, int overallRating,
                               int atmosphereRating, int organizationRating, String feedback, String vibeTagsJson,
                               Instant createdAt, Instant updatedAt, Instant editedUntil) {
    }

    public record ReviewSummary(int count, double avgOverall, double avgAtmosphere, double avgOrganization) {
    }
}