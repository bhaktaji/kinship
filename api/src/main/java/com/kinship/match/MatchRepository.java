package com.kinship.match;

import com.kinship.match.dto.MatchDtos.MemberPreview;
import com.kinship.user.InterestRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class MatchRepository {

    private final JdbcClient jdbc;

    public MatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<JobRow> findActiveJob(UUID userId) {
        return jdbc.sql("""
                        SELECT id, user_id, status, preferences::text, error_code, created_at, started_at
                          FROM match_jobs
                         WHERE user_id = :userId AND status IN ('QUEUED', 'RUNNING')
                         LIMIT 1
                        """)
                .param("userId", userId).query(this::mapJob).optional();
    }

    public Optional<JobRow> findJob(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, user_id, status, preferences::text, error_code, created_at, started_at
                          FROM match_jobs WHERE id = :id
                        """)
                .param("id", jobId).query(this::mapJob).optional();
    }

    public UUID insertJob(UUID userId, String preferencesJson) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO match_jobs (id, user_id, status, preferences)
                        VALUES (:id, :userId, 'QUEUED', CAST(:preferences AS jsonb))
                        """)
                .param("id", id).param("userId", userId).param("preferences", preferencesJson).update();
        return id;
    }

    public Optional<JobRow> claimNextJob() {
        return jdbc.sql("""
                        UPDATE match_jobs SET status = 'RUNNING', started_at = now()
                         WHERE id = (SELECT id FROM match_jobs
                                      WHERE status = 'QUEUED'
                                      ORDER BY created_at
                                      FOR UPDATE SKIP LOCKED
                                      LIMIT 1)
                        RETURNING id, user_id, status, preferences::text, error_code, created_at, started_at
                        """)
                .query(this::mapJob).optional();
    }

    public void markDone(UUID jobId) {
        jdbc.sql("UPDATE match_jobs SET status = 'DONE', completed_at = now() WHERE id = :id")
                .param("id", jobId).update();
    }

    public void markFailed(UUID jobId, String errorCode) {
        jdbc.sql("UPDATE match_jobs SET status = 'FAILED', error_code = :code, completed_at = now() WHERE id = :id")
                .param("id", jobId).param("code", errorCode).update();
    }

    public int recoverStale() {
        return jdbc.sql("""
                        UPDATE match_jobs SET status = 'FAILED', error_code = 'JOB_TIMEOUT', completed_at = now()
                         WHERE status = 'RUNNING' AND started_at < now() - interval '5 minutes'
                        """).update();
    }

    public List<CandidateRow> candidates(UUID userId, List<String> cells, int maxDistanceKm) {
        return jdbc.sql("""
                        SELECT u.id, u.display_name,
                               ST_Y(u.location::geometry) AS lat, ST_X(u.location::geometry) AS lng,
                               ST_Distance(u.location, me.location) AS distance_m,
                               u.availability::text AS availability
                          FROM users u
                          JOIN users me ON me.id = :userId
                         WHERE u.status = 'ACTIVE' AND u.id <> :userId
                           AND u.location IS NOT NULL AND me.location IS NOT NULL
                           AND u.h3_cell_r7 = ANY(string_to_array(:cells, ',')::text[])
                           AND ST_DWithin(u.location, me.location, :maxMeters)
                           AND NOT EXISTS (
                               SELECT 1 FROM group_members g1
                                 JOIN group_members g2 ON g1.group_id = g2.group_id
                                WHERE g1.user_id = :userId AND g2.user_id = u.id
                                  AND g1.status = 'ACTIVE' AND g2.status = 'ACTIVE')
                           AND NOT EXISTS (
                               SELECT 1 FROM match_invitations mi
                                 JOIN match_groups mg ON mg.id = mi.match_group_id
                                WHERE mi.user_id = u.id AND mi.status = 'INVITED'
                                  AND mg.status = 'INVITED' AND mg.expires_at > now())
                         ORDER BY distance_m
                         LIMIT 500
                        """)
                .param("userId", userId)
                .param("cells", String.join(",", cells))
                .param("maxMeters", maxDistanceKm * 1000.0)
                .query((rs, rowNum) -> new CandidateRow(
                        rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getDouble("lat"), rs.getDouble("lng"), rs.getDouble("distance_m"),
                        rs.getString("availability")))
                .list();
    }

    public Map<UUID, List<InterestRecord>> interestsFor(List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String ids = userIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        Map<UUID, List<InterestRecord>> result = new HashMap<>();
        jdbc.sql("""
                        SELECT ui.user_id, i.id, i.slug, i.name, i.category
                          FROM user_interests ui JOIN interests i ON i.id = ui.interest_id
                         WHERE ui.user_id = ANY(string_to_array(:ids, ',')::uuid[])
                        """)
                .param("ids", ids)
                .query((rs, rowNum) -> {
                    result.computeIfAbsent(rs.getObject("user_id", UUID.class), key -> new ArrayList<>())
                            .add(new InterestRecord(rs.getInt("id"), rs.getString("slug"),
                                    rs.getString("name"), rs.getString("category")));
                    return null;
                }).list();
        return result;
    }

    public Map<UUID, Integer> sharedGroupCounts(UUID userId, List<UUID> candidateIds) {
        if (candidateIds.isEmpty()) {
            return Map.of();
        }
        String ids = candidateIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        Map<UUID, Integer> result = new HashMap<>();
        jdbc.sql("""
                        SELECT gm2.user_id, count(distinct gm1.group_id) AS shared
                          FROM group_members gm1
                          JOIN group_members gm2 ON gm1.group_id = gm2.group_id
                          JOIN groups g ON g.id = gm1.group_id
                         WHERE gm1.user_id = :userId AND gm1.status = 'ACTIVE'
                           AND gm2.status = 'ACTIVE'
                           AND gm2.user_id = ANY(string_to_array(:ids, ',')::uuid[])
                           AND g.created_at >= now() - interval '90 days'
                         GROUP BY gm2.user_id
                        """)
                .param("userId", userId).param("ids", ids)
                .query((rs, rowNum) -> {
                    result.put(rs.getObject("user_id", UUID.class), rs.getInt("shared"));
                    return null;
                }).list();
        return result;
    }

    public Map<String, Integer> pairSharedCounts(List<UUID> userIds) {
        if (userIds.size() < 2) {
            return Map.of();
        }
        String ids = userIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        Map<String, Integer> result = new HashMap<>();
        jdbc.sql("""
                        SELECT a.user_id AS left_id, b.user_id AS right_id,
                               count(distinct a.group_id) AS shared
                          FROM group_members a
                          JOIN group_members b ON a.group_id = b.group_id
                          JOIN groups g ON g.id = a.group_id
                         WHERE a.user_id = ANY(string_to_array(:ids, ',')::uuid[])
                           AND b.user_id = ANY(string_to_array(:ids, ',')::uuid[])
                           AND a.user_id < b.user_id
                           AND a.status = 'ACTIVE' AND b.status = 'ACTIVE'
                           AND g.created_at >= now() - interval '90 days'
                         GROUP BY a.user_id, b.user_id
                        """)
                .param("ids", ids)
                .query((rs, rowNum) -> {
                    result.put(pairKey(rs.getObject("left_id", UUID.class), rs.getObject("right_id", UUID.class)),
                            rs.getInt("shared"));
                    return null;
                }).list();
        return result;
    }

    public UUID insertMatchGroup(UUID jobId, List<UUID> memberIds, double score, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        String ids = memberIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        jdbc.sql("""
                        INSERT INTO match_groups (id, job_id, member_user_ids, compatibility_score, status, expires_at)
                        VALUES (:id, :jobId, CAST(string_to_array(:ids, ',') AS uuid[]), :score, 'INVITED', :expiresAt)
                        ON CONFLICT (job_id) DO NOTHING
                        """)
                .param("id", id).param("jobId", jobId).param("ids", ids)
                .param("score", score).param("expiresAt", Timestamp.from(expiresAt)).update();
        for (UUID memberId : memberIds) {
            jdbc.sql("""
                            INSERT INTO match_invitations (id, match_group_id, user_id, status)
                            VALUES (:id, :matchGroupId, :userId, 'INVITED')
                            ON CONFLICT (match_group_id, user_id) DO NOTHING
                            """)
                    .param("id", UUID.randomUUID()).param("matchGroupId", id)
                    .param("userId", memberId).update();
        }
        return id;
    }

    public List<MatchGroupRow> matchGroupsForJob(UUID jobId) {
        return jdbc.sql("""
                        SELECT mg.id, mg.job_id, mg.member_user_ids, mg.compatibility_score, mg.status,
                               mg.group_id, mg.expires_at, mj.user_id AS requester_id
                          FROM match_groups mg JOIN match_jobs mj ON mj.id = mg.job_id
                         WHERE mg.job_id = :jobId
                         ORDER BY mg.created_at DESC
                        """)
                .param("jobId", jobId)
                .query((rs, rowNum) -> new MatchGroupRow(
                        rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                        readIds(rs, "member_user_ids"), rs.getDouble("compatibility_score"),
                        rs.getString("status"), rs.getObject("group_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("requester_id", UUID.class)))
                .list();
    }

    public Optional<InvitationRow> findInvitationForUpdate(UUID invitationId) {
        return jdbc.sql("""
                        SELECT mi.id, mi.match_group_id, mi.user_id, mi.status,
                               mg.group_id, mg.expires_at, mg.member_user_ids, mg.compatibility_score,
                               mj.user_id AS requester_id
                          FROM match_invitations mi
                          JOIN match_groups mg ON mg.id = mi.match_group_id
                          JOIN match_jobs mj ON mj.id = mg.job_id
                         WHERE mi.id = :id
                         FOR UPDATE OF mi, mg
                        """)
                .param("id", invitationId)
                .query((rs, rowNum) -> new InvitationRow(
                        rs.getObject("id", UUID.class), rs.getObject("match_group_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("status"),
                        rs.getObject("group_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        readIds(rs, "member_user_ids"), rs.getDouble("compatibility_score"),
                        rs.getObject("requester_id", UUID.class)))
                .optional();
    }

    public void updateInvitation(UUID invitationId, String status) {
        jdbc.sql("UPDATE match_invitations SET status = :status, responded_at = now() WHERE id = :id")
                .param("id", invitationId).param("status", status).update();
    }

    public List<UUID> acceptedUserIds(UUID matchGroupId) {
        return jdbc.sql("""
                        SELECT user_id FROM match_invitations
                         WHERE match_group_id = :id AND status = 'ACCEPTED'
                        """)
                .param("id", matchGroupId).query(UUID.class).list();
    }

    public boolean isAccepted(UUID matchGroupId, UUID userId) {
        Integer count = jdbc.sql("""
                        SELECT count(*) FROM match_invitations
                         WHERE match_group_id = :id AND user_id = :userId AND status = 'ACCEPTED'
                        """)
                .param("id", matchGroupId).param("userId", userId)
                .query(Integer.class).single();
        return count != null && count > 0;
    }

    public String jobPreferencesForMatchGroup(UUID matchGroupId) {
        return jdbc.sql("""
                        SELECT mj.preferences::text
                          FROM match_groups mg JOIN match_jobs mj ON mj.id = mg.job_id
                         WHERE mg.id = :id
                        """)
                .param("id", matchGroupId).query(String.class).optional().orElse("{}");
    }

    public int countInvitations(UUID matchGroupId, String status) {
        Integer count = jdbc.sql("SELECT count(*) FROM match_invitations WHERE match_group_id = :id AND status = :status")
                .param("id", matchGroupId).param("status", status).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public void expireMatchGroup(UUID matchGroupId) {
        jdbc.sql("UPDATE match_groups SET status = 'EXPIRED' WHERE id = :id AND status = 'INVITED'")
                .param("id", matchGroupId).update();
        jdbc.sql("""
                        UPDATE match_invitations SET status = 'EXPIRED'
                         WHERE match_group_id = :id AND status = 'INVITED'
                        """)
                .param("id", matchGroupId).update();
    }

    public void confirmMatchGroup(UUID matchGroupId, UUID groupId) {
        jdbc.sql("""
                        UPDATE match_groups SET status = 'CONFIRMED', group_id = :groupId
                         WHERE id = :id
                        """)
                .param("id", matchGroupId).param("groupId", groupId).update();
        jdbc.sql("""
                        UPDATE match_invitations SET status = 'EXPIRED'
                         WHERE match_group_id = :id AND status = 'INVITED'
                        """)
                .param("id", matchGroupId).update();
    }

    public int expireDueGroups() {
        List<UUID> ids = jdbc.sql("""
                        UPDATE match_groups SET status = 'EXPIRED'
                         WHERE status = 'INVITED' AND expires_at <= now()
                        RETURNING id
                        """).query(UUID.class).list();
        for (UUID id : ids) {
            jdbc.sql("""
                            UPDATE match_invitations SET status = 'EXPIRED'
                             WHERE match_group_id = :id AND status = 'INVITED'
                            """)
                    .param("id", id).update();
        }
        return ids.size();
    }

    public List<InvitationRow> invitationsForUser(UUID userId, String status) {
        return jdbc.sql("""
                        SELECT mi.id, mi.match_group_id, mi.user_id, mi.status,
                               mg.group_id, mg.expires_at, mg.member_user_ids, mg.compatibility_score,
                               mj.user_id AS requester_id
                          FROM match_invitations mi
                          JOIN match_groups mg ON mg.id = mi.match_group_id
                          JOIN match_jobs mj ON mj.id = mg.job_id
                         WHERE mi.user_id = :userId
                           AND (CAST(:status AS text) IS NULL OR mi.status = :status)
                         ORDER BY mg.created_at DESC
                         LIMIT 5
                        """)
                .param("userId", userId).param("status", status)
                .query((rs, rowNum) -> new InvitationRow(
                        rs.getObject("id", UUID.class), rs.getObject("match_group_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("status"),
                        rs.getObject("group_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        readIds(rs, "member_user_ids"), rs.getDouble("compatibility_score"),
                        rs.getObject("requester_id", UUID.class)))
                .list();
    }

    public List<MemberPreview> memberPreviews(UUID matchGroupId, UUID viewerId) {
        MatchGroupRow group = jdbc.sql("""
                        SELECT id, job_id, member_user_ids, compatibility_score, status, group_id, expires_at,
                               (SELECT user_id FROM match_jobs WHERE id = job_id) AS requester_id
                          FROM match_groups WHERE id = :id
                        """)
                .param("id", matchGroupId)
                .query((rs, rowNum) -> new MatchGroupRow(
                        rs.getObject("id", UUID.class), rs.getObject("job_id", UUID.class),
                        readIds(rs, "member_user_ids"), rs.getDouble("compatibility_score"),
                        rs.getString("status"), rs.getObject("group_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("requester_id", UUID.class)))
                .optional().orElse(null);
        if (group == null || group.memberIds().isEmpty()) {
            return List.of();
        }
        String ids = group.memberIds().stream().map(UUID::toString).collect(Collectors.joining(","));
        Map<UUID, MemberBase> users = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT u.id, u.display_name,
                               COALESCE(ST_Distance(u.location, viewer.location) / 1000.0, 0) AS distance_km
                          FROM users u, users viewer
                         WHERE viewer.id = :viewerId
                           AND u.id = ANY(string_to_array(:ids, ',')::uuid[])
                        """)
                .param("viewerId", viewerId).param("ids", ids)
                .query((rs, rowNum) -> {
                    users.put(rs.getObject("id", UUID.class), new MemberBase(rs.getString("display_name"),
                            rs.getDouble("distance_km")));
                    return null;
                }).list();
        Map<UUID, List<InterestRecord>> interests = interestsFor(group.memberIds());
        List<MemberPreview> previews = new ArrayList<>();
        for (UUID id : group.memberIds()) {
            MemberBase base = users.get(id);
            if (base == null) {
                continue;
            }
            List<String> names = interests.getOrDefault(id, List.of()).stream().map(InterestRecord::name).toList();
            previews.add(new MemberPreview(id, base.displayName(), names, base.distanceKm()));
        }
        return previews;
    }

    private String pairKey(UUID left, UUID right) {
        return left.toString().compareTo(right.toString()) < 0
                ? left + "|" + right : right + "|" + left;
    }

    private List<UUID> readIds(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<UUID> ids = new ArrayList<>(values.length);
        for (Object value : values) {
            ids.add(value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()));
        }
        return ids;
    }

    private JobRow mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new JobRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("status"), rs.getString("preferences"), rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant());
    }

    public record JobRow(UUID id, UUID userId, String status, String preferences, String errorCode,
                         Instant createdAt, Instant startedAt) {
    }

    public record CandidateRow(UUID id, String displayName, double lat, double lng,
                               double distanceM, String availabilityJson) {
    }

    public record MatchGroupRow(UUID id, UUID jobId, List<UUID> memberIds, double score,
                                String status, UUID groupId, Instant expiresAt, UUID requesterId) {
    }

    public record InvitationRow(UUID id, UUID matchGroupId, UUID userId, String status,
                                UUID groupId, Instant expiresAt, List<UUID> memberIds,
                                double score, UUID requesterId) {
    }

    private record MemberBase(String displayName, double distanceKm) {
    }
}
