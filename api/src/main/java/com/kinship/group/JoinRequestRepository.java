package com.kinship.group;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JoinRequestRepository {
    private final JdbcClient jdbc;

    public JoinRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID groupId, UUID userId, String reason, boolean shareWorkplace) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO group_join_requests (id, group_id, user_id, reason, share_workplace, status)
                        VALUES (:id, :groupId, :userId, :reason, :share, 'PENDING')
                        """)
                .param("id", id).param("groupId", groupId).param("userId", userId)
                .param("reason", reason).param("share", shareWorkplace).update();
        return id;
    }

    public Optional<JoinRequestRecord> findPending(UUID groupId, UUID userId) {
        return jdbc.sql("""
                        SELECT id, group_id, user_id, reason, share_workplace, status, created_at
                          FROM group_join_requests
                         WHERE group_id = :groupId AND user_id = :userId AND status = 'PENDING'
                        """)
                .param("groupId", groupId).param("userId", userId)
                .query(this::map).optional();
    }

    public List<JoinRequestRecord> listPending(UUID groupId) {
        return jdbc.sql("""
                        SELECT jr.id, jr.group_id, jr.user_id, jr.reason, jr.share_workplace, jr.status, jr.created_at,
                               u.display_name, u.bio, u.hobbies::text AS hobbies, u.profession, u.workplace, u.workplace_shared,
                               u.profile_photo_asset_id, u.availability::text AS availability
                          FROM group_join_requests jr JOIN users u ON u.id = jr.user_id
                         WHERE jr.group_id = :groupId AND jr.status = 'PENDING'
                         ORDER BY jr.created_at
                        """)
                .param("groupId", groupId)
                .query(this::mapWithProfile).list();
    }

    public void decide(UUID id, String status, UUID decidedBy, String reason) {
        jdbc.sql("""
                        UPDATE group_join_requests
                           SET status = :status, decision_by = :decidedBy, decision_reason = :reason, decided_at = now()
                         WHERE id = :id
                        """)
                .param("id", id).param("status", status).param("decidedBy", decidedBy)
                .param("reason", reason).update();
    }

    public Optional<Instant> lastRejectedAt(UUID groupId, UUID userId) {
        return jdbc.sql("""
                        SELECT decided_at FROM group_join_requests
                         WHERE group_id = :groupId AND user_id = :userId AND status = 'REJECTED'
                         ORDER BY decided_at DESC LIMIT 1
                        """)
                .param("groupId", groupId).param("userId", userId)
                .query((rs, n) -> rs.getTimestamp("decided_at").toInstant()).optional();
    }

    public Optional<JoinRequestRecord> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, group_id, user_id, reason, share_workplace, status, created_at
                          FROM group_join_requests WHERE id = :id
                        """)
                .param("id", id).query(this::map).optional();
    }

    private JoinRequestRecord map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new JoinRequestRecord(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("reason"), rs.getBoolean("share_workplace"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                null, null, null, null, null, false, null, null);
    }

    private JoinRequestRecord mapWithProfile(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new JoinRequestRecord(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("reason"), rs.getBoolean("share_workplace"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("display_name"), rs.getString("bio"), rs.getString("hobbies"),
                rs.getString("profession"), rs.getString("workplace"), rs.getBoolean("workplace_shared"),
                rs.getObject("profile_photo_asset_id", UUID.class), rs.getString("availability"));
    }

    public record JoinRequestRecord(UUID id, UUID groupId, UUID userId, String reason, boolean shareWorkplace,
                                   String status, Instant createdAt,
                                   String displayName, String bio, String hobbiesJson, String profession,
                                   String workplace, boolean workplaceShared, UUID profilePhotoAssetId,
                                   String availabilityJson) {
    }
}