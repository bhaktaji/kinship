package com.kinship.group;

import com.kinship.group.dto.GroupDtos.GroupCard;
import com.kinship.group.dto.GroupDtos.GroupResponse;
import com.kinship.group.dto.GroupDtos.MemberResponse;
import com.kinship.user.dto.LocationResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GroupRepository {

    private final JdbcClient jdbc;

    public GroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GroupRow> findGroup(UUID id) {
        return jdbc.sql("""
                        SELECT g.id, g.name, g.description, g.category, g.activity_id,
                               ST_Y(g.location::geometry) AS lat, ST_X(g.location::geometry) AS lng,
                               g.max_members, g.visibility, g.status, g.created_by, g.created_at,
                               g.join_policy, g.banner_asset_id,
                               (SELECT count(*) FROM group_members gm
                                 WHERE gm.group_id = g.id AND gm.status = 'ACTIVE') AS active_count
                          FROM groups g WHERE g.id = :id
                        """)
                .param("id", id).query(this::mapGroup).optional();
    }

    public Optional<GroupLock> lockGroup(UUID id) {
        return jdbc.sql("SELECT id, status, max_members, visibility, join_policy FROM groups WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, rowNum) -> new GroupLock(rs.getObject("id", UUID.class),
                        rs.getString("status"), rs.getInt("max_members"), rs.getString("visibility"),
                        rs.getString("join_policy")))
                .optional();
    }

    public Optional<Membership> membership(UUID groupId, UUID userId) {
        return jdbc.sql("SELECT role, status FROM group_members WHERE group_id = :groupId AND user_id = :userId")
                .param("groupId", groupId).param("userId", userId)
                .query((rs, rowNum) -> new Membership(rs.getString("role"), rs.getString("status")))
                .optional();
    }

    public int activeMemberCount(UUID groupId) {
        Integer count = jdbc.sql("SELECT count(*) FROM group_members WHERE group_id = :groupId AND status = 'ACTIVE'")
                .param("groupId", groupId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    /** Active member ids, used to filter WebSocket fan-out after a removal or leave. */
    public List<UUID> activeMemberIds(UUID groupId) {
        return jdbc.sql("SELECT user_id FROM group_members WHERE group_id = :groupId AND status = 'ACTIVE'")
                .param("groupId", groupId)
                .query(UUID.class).list();
    }

    public List<MemberResponse> members(UUID groupId, boolean includePending) {
        return jdbc.sql("""
                        SELECT gm.user_id, u.display_name, gm.role, gm.status, gm.joined_at, u.profile_photo_asset_id
                          FROM group_members gm JOIN users u ON u.id = gm.user_id
                         WHERE gm.group_id = :groupId
                           AND gm.status IN ('ACTIVE', 'PENDING')
                           AND (CAST(:includePending AS boolean) OR gm.status = 'ACTIVE')
                         ORDER BY CASE WHEN gm.role = 'OWNER' THEN 0 ELSE 1 END, gm.joined_at
                        """)
                .param("groupId", groupId).param("includePending", includePending)
                .query((rs, rowNum) -> new MemberResponse(rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"), rs.getString("role"), rs.getString("status"),
                        rs.getTimestamp("joined_at").toInstant(),
                        rs.getObject("profile_photo_asset_id", UUID.class)))
                .list();
    }

    public UUID insertGroup(UUID id, String name, String description, String category, UUID activityId,
                            double lat, double lng, int maxMembers, String visibility, String joinPolicy, UUID createdBy) {
        jdbc.sql("""
                        INSERT INTO groups (id, name, description, category, activity_id, location,
                                            max_members, visibility, join_policy, status, created_by)
                        VALUES (:id, :name, :description, :category, :activityId,
                                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                :maxMembers, :visibility, :joinPolicy, 'ACTIVE', :createdBy)
                        """)
                .param("id", id).param("name", name).param("description", description)
                .param("category", category).param("activityId", activityId)
                .param("lat", lat).param("lng", lng).param("maxMembers", maxMembers)
                .param("visibility", visibility).param("joinPolicy", joinPolicy == null ? "OPEN" : joinPolicy)
                .param("createdBy", createdBy).update();
        return id;
    }

    public void upsertMembership(UUID groupId, UUID userId, String role, String status) {
        jdbc.sql("""
                        INSERT INTO group_members (group_id, user_id, role, status)
                        VALUES (:groupId, :userId, :role, :status)
                        ON CONFLICT (group_id, user_id) DO UPDATE
                        SET role = EXCLUDED.role, status = EXCLUDED.status, updated_at = now()
                        """)
                .param("groupId", groupId).param("userId", userId)
                .param("role", role).param("status", status).update();
    }

    public void updateGroup(UUID id, String name, String description, Integer maxMembers, String visibility,
                            String joinPolicy, UUID bannerAssetId) {
        jdbc.sql("""
                        UPDATE groups
                           SET name = COALESCE(:name, name),
                               description = COALESCE(:description, description),
                               max_members = COALESCE(:maxMembers, max_members),
                               visibility = COALESCE(:visibility, visibility),
                               join_policy = COALESCE(:joinPolicy, join_policy),
                               banner_asset_id = COALESCE(:banner, banner_asset_id),
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", id).param("name", name).param("description", description)
                .param("maxMembers", maxMembers).param("visibility", visibility)
                .param("joinPolicy", joinPolicy).param("banner", bannerAssetId).update();
    }

    public Optional<UUID> successorOwner(UUID groupId, UUID excludedUserId) {
        return jdbc.sql("""
                        SELECT user_id FROM group_members
                         WHERE group_id = :groupId AND status = 'ACTIVE' AND user_id <> :excludedUserId
                         ORDER BY joined_at, user_id LIMIT 1
                        """)
                .param("groupId", groupId).param("excludedUserId", excludedUserId)
                .query(UUID.class).optional();
    }

    public void archiveGroup(UUID groupId) {
        jdbc.sql("UPDATE groups SET status = 'ARCHIVED', updated_at = now() WHERE id = :id")
                .param("id", groupId).update();
        jdbc.sql("""
                        UPDATE group_members SET status = 'LEFT', updated_at = now()
                         WHERE group_id = :groupId AND status IN ('ACTIVE', 'PENDING')
                        """)
                .param("groupId", groupId).update();
    }

    public Optional<ActivityLocation> activityLocation(UUID activityId) {
        return jdbc.sql("""
                        SELECT ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lng
                          FROM activities WHERE id = :id AND status = 'ACTIVE'
                        """)
                .param("id", activityId)
                .query((rs, rowNum) -> new ActivityLocation(rs.getDouble("lat"), rs.getDouble("lng")))
                .optional();
    }

    public List<MyGroupRow> myGroups(UUID userId, Instant cursorJoinedAt, UUID cursorGroupId, int limit) {
        return jdbc.sql("""
                        SELECT g.id, g.name, g.category, g.visibility, g.max_members, gm.status AS my_status,
                               gm.joined_at, e.event_id, e.start_time,
                               (SELECT count(*) FROM group_members x
                                 WHERE x.group_id = g.id AND x.status = 'ACTIVE') AS member_count
                          FROM group_members gm
                          JOIN groups g ON g.id = gm.group_id
                          LEFT JOIN LATERAL (
                               SELECT ev.id AS event_id, ev.start_time
                                 FROM events ev
                                WHERE ev.group_id = g.id AND ev.status = 'SCHEDULED' AND ev.start_time >= now()
                                ORDER BY ev.start_time LIMIT 1
                          ) e ON TRUE
                         WHERE gm.user_id = :userId
                           AND gm.status IN ('ACTIVE', 'PENDING')
                           AND g.status = 'ACTIVE'
                           AND (CAST(:cursorJoined AS timestamptz) IS NULL
                                OR gm.joined_at < CAST(:cursorJoined AS timestamptz)
                                OR (gm.joined_at = CAST(:cursorJoined AS timestamptz)
                                    AND g.id < CAST(:cursorGroup AS uuid)))
                         ORDER BY gm.joined_at DESC, g.id DESC
                         LIMIT :limit
                        """)
                .param("userId", userId)
                .param("cursorJoined", cursorJoinedAt == null ? null : Timestamp.from(cursorJoinedAt))
                .param("cursorGroup", cursorGroupId)
                .param("limit", limit)
                .query((rs, rowNum) -> new MyGroupRow(new GroupCard(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"),
                        rs.getString("visibility"), rs.getInt("max_members"), rs.getInt("member_count"),
                        rs.getString("my_status"), rs.getObject("event_id", UUID.class),
                        rs.getTimestamp("start_time") == null ? null : rs.getTimestamp("start_time").toInstant()),
                        rs.getTimestamp("joined_at").toInstant()))
                .list();
    }

    public GroupResponse toResponse(GroupRow row, String myRole, String myStatus) {
        return new GroupResponse(row.id(), row.name(), row.description(), row.category(), row.activityId(),
                new LocationResponse(row.lat(), row.lng()), row.maxMembers(), row.visibility(), row.status(),
                row.joinPolicy(), row.createdBy(), row.createdAt(), row.activeCount(), myRole, myStatus);
    }

    private GroupRow mapGroup(ResultSet rs, int rowNum) throws SQLException {
        return new GroupRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getString("category"), rs.getObject("activity_id", UUID.class),
                rs.getDouble("lat"), rs.getDouble("lng"), rs.getInt("max_members"),
                rs.getString("visibility"), rs.getString("status"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getInt("active_count"), rs.getString("join_policy"));
    }

    public record GroupRow(UUID id, String name, String description, String category, UUID activityId,
                           double lat, double lng, int maxMembers, String visibility, String status,
                           UUID createdBy, Instant createdAt, int activeCount, String joinPolicy) {
    }

    public record GroupLock(UUID id, String status, int maxMembers, String visibility, String joinPolicy) {
    }

    public record Membership(String role, String status) {
    }

    public record ActivityLocation(double lat, double lng) {
    }

    public record MyGroupRow(GroupCard card, Instant joinedAt) {
    }
}
