package com.kinship.activity;

import com.kinship.activity.dto.ActivityCard;
import com.kinship.activity.dto.ActivityDetailResponse;
import com.kinship.activity.dto.ActivityLocation;
import com.kinship.activity.dto.GroupSummary;
import com.kinship.activity.dto.NextEvent;
import com.kinship.activity.dto.OrganizationSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ActivityRepository {

    private final JdbcClient jdbc;

    public ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ActivityCard> findNearby(double lat, double lng, double radiusMeters,
                                         double minLat, double minLng, double maxLat, double maxLng,
                                         String category) {
        return jdbc.sql("""
                        SELECT a.id               AS activity_id,
                               a.title,
                               a.category,
                               a.description,
                               a.image_url,
                               a.address,
                               ST_Y(a.location::geometry) AS lat,
                               ST_X(a.location::geometry) AS lng,
                               ST_Distance(a.location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS distance_m,
                               g.id               AS group_id,
                               g.name             AS group_name,
                               g.member_count,
                               g.max_members,
                               e.event_id,
                               e.start_time
                          FROM activities a
                          JOIN LATERAL (
                               SELECT gr.id, gr.name, gr.max_members, mc.active_count AS member_count
                                 FROM groups gr
                                 JOIN LATERAL (
                                      SELECT count(*) AS active_count
                                        FROM group_members gm
                                       WHERE gm.group_id = gr.id AND gm.status = 'ACTIVE'
                                 ) mc ON TRUE
                                WHERE gr.activity_id = a.id
                                  AND gr.visibility = 'public'
                                  AND gr.status = 'ACTIVE'
                                  AND mc.active_count < gr.max_members
                                ORDER BY gr.created_at
                                LIMIT 1
                          ) g ON TRUE
                          JOIN LATERAL (
                               SELECT ev.id AS event_id, ev.start_time
                                 FROM events ev
                                WHERE ev.group_id = g.id
                                  AND ev.status = 'SCHEDULED'
                                  AND ev.start_time >= now()
                                ORDER BY ev.start_time
                                LIMIT 1
                          ) e ON TRUE
                         WHERE a.status = 'ACTIVE'
                           AND a.location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)::geography
                           AND ST_DWithin(a.location,
                                          ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                          :radius)
                           AND (CAST(:category AS text) IS NULL OR a.category = :category)
                         ORDER BY distance_m, a.id
                         LIMIT 200
                        """)
                .param("lat", lat)
                .param("lng", lng)
                .param("radius", radiusMeters)
                .param("minLat", minLat)
                .param("minLng", minLng)
                .param("maxLat", maxLat)
                .param("maxLng", maxLng)
                .param("category", category)
                .query(this::mapCard)
                .list();
    }

    public boolean categoryExists(String category) {
        Integer count = jdbc.sql("SELECT count(*) FROM activities WHERE category = :category")
                .param("category", category)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public Optional<ActivityDetailResponse> findDetail(UUID activityId) {
        Optional<ActivityRow> row = jdbc.sql("""
                        SELECT a.id, a.title, a.description, a.category, a.image_url, a.address,
                               ST_Y(a.location::geometry) AS lat,
                               ST_X(a.location::geometry) AS lng,
                               o.id AS org_id, o.name AS org_name, o.type AS org_type
                          FROM activities a
                          LEFT JOIN organizations o ON o.id = a.organization_id
                         WHERE a.id = :id AND a.status = 'ACTIVE'
                        """)
                .param("id", activityId)
                .query((rs, rowNum) -> new ActivityRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("category"),
                        rs.getString("image_url"),
                        rs.getString("address"),
                        rs.getDouble("lat"),
                        rs.getDouble("lng"),
                        rs.getObject("org_id", UUID.class),
                        rs.getString("org_name"),
                        rs.getString("org_type")))
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        ActivityRow a = row.get();
        OrganizationSummary organization = a.orgId() == null ? null
                : new OrganizationSummary(a.orgId(), a.orgName(), a.orgType());
        return Optional.of(new ActivityDetailResponse(a.id(), a.title(), a.description(), a.category(),
                a.imageUrl(), new ActivityLocation(a.lat(), a.lng(), a.address()), organization,
                findGroups(activityId)));
    }

    private List<GroupSummary> findGroups(UUID activityId) {
        return jdbc.sql("""
                        SELECT g.id, g.name, g.visibility, g.max_members, mc.active_count,
                               e.event_id, e.start_time
                          FROM groups g
                          JOIN LATERAL (
                               SELECT count(*) AS active_count
                                 FROM group_members gm
                                WHERE gm.group_id = g.id AND gm.status = 'ACTIVE'
                          ) mc ON TRUE
                          LEFT JOIN LATERAL (
                               SELECT ev.id AS event_id, ev.start_time
                                 FROM events ev
                                WHERE ev.group_id = g.id
                                  AND ev.status = 'SCHEDULED'
                                  AND ev.start_time >= now()
                                ORDER BY ev.start_time
                                LIMIT 1
                          ) e ON TRUE
                         WHERE g.activity_id = :activityId
                           AND g.visibility = 'public'
                           AND g.status = 'ACTIVE'
                         ORDER BY g.created_at
                        """)
                .param("activityId", activityId)
                .query((rs, rowNum) -> {
                    UUID eventId = rs.getObject("event_id", UUID.class);
                    java.sql.Timestamp startTime = rs.getTimestamp("start_time");
                    NextEvent next = eventId == null || startTime == null ? null
                            : new NextEvent(eventId, startTime.toInstant());
                    return new GroupSummary(rs.getObject("id", UUID.class), rs.getString("name"),
                            rs.getString("visibility"), rs.getInt("active_count"),
                            rs.getInt("max_members"), next);
                })
                .list();
    }

    private ActivityCard mapCard(ResultSet rs, int rowNum) throws SQLException {
        UUID eventId = rs.getObject("event_id", UUID.class);
        Instant startTime = rs.getTimestamp("start_time").toInstant();
        return new ActivityCard(
                rs.getObject("activity_id", UUID.class),
                rs.getString("title"),
                rs.getString("category"),
                rs.getString("description"),
                rs.getString("image_url"),
                new ActivityLocation(rs.getDouble("lat"), rs.getDouble("lng"), rs.getString("address")),
                Math.round(rs.getDouble("distance_m")),
                rs.getObject("group_id", UUID.class),
                rs.getString("group_name"),
                rs.getInt("member_count"),
                rs.getInt("max_members"),
                new NextEvent(eventId, startTime));
    }

    private record ActivityRow(UUID id, String title, String description, String category,
                               String imageUrl, String address, double lat, double lng,
                               UUID orgId, String orgName, String orgType) {
    }
}
