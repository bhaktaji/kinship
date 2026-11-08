package com.kinship.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.event.dto.EventDtos;
import com.kinship.event.dto.EventDtos.EventDetailResponse;
import com.kinship.event.dto.EventDtos.EventSummary;
import com.kinship.event.dto.EventDtos.GroupRef;
import com.kinship.event.dto.EventDtos.RsvpCounts;
import com.kinship.user.dto.LocationResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EventRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public EventRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<SeriesRow> findSeries(UUID id) {
        return jdbc.sql("""
                        SELECT s.id, s.group_id, s.title, s.description, s.duration_min, s.capacity,
                               ST_Y(COALESCE(s.location, g.location)::geometry) AS lat,
                               ST_X(COALESCE(s.location, g.location)::geometry) AS lng,
                               s.timezone, s.recurrence_rule::text AS recurrence_rule,
                               s.starts_on, s.until_date, s.generated_through, s.status, s.created_by,
                               g.activity_id
                          FROM event_series s
                          JOIN groups g ON g.id = s.group_id
                         WHERE s.id = :id
                        """)
                .param("id", id)
                .query(this::mapSeries)
                .optional();
    }

    public Optional<SeriesRow> findSeriesByGroupTitleStart(UUID groupId, String title, LocalDate startsOn) {
        return jdbc.sql("""
                        SELECT s.id, s.group_id, s.title, s.description, s.duration_min, s.capacity,
                               ST_Y(COALESCE(s.location, g.location)::geometry) AS lat,
                               ST_X(COALESCE(s.location, g.location)::geometry) AS lng,
                               s.timezone, s.recurrence_rule::text AS recurrence_rule,
                               s.starts_on, s.until_date, s.generated_through, s.status, s.created_by,
                               g.activity_id
                          FROM event_series s
                          JOIN groups g ON g.id = s.group_id
                         WHERE s.group_id = :groupId AND s.title = :title AND s.starts_on = :startsOn
                        """)
                .param("groupId", groupId)
                .param("title", title)
                .param("startsOn", java.sql.Date.valueOf(startsOn))
                .query(this::mapSeries)
                .optional();
    }

    public UUID insertSeries(UUID groupId, String title, String description, int durationMin, int capacity,
                             Double lat, Double lng, String timezone, String recurrenceJson,
                             LocalDate startsOn, LocalDate until, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO event_series (id, group_id, title, description, duration_min, capacity,
                                                  location, timezone, recurrence_rule, starts_on, until_date,
                                                  generated_through, status, created_by)
                        VALUES (:id, :groupId, :title, :description, :durationMin, :capacity,
                                CASE WHEN CAST(:lat AS double precision) IS NULL THEN NULL
                                     ELSE ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography END,
                                :timezone, CAST(:rule AS jsonb), :startsOn, :until, NULL, 'ACTIVE', :createdBy)
                        """)
                .param("id", id).param("groupId", groupId).param("title", title)
                .param("description", description).param("durationMin", durationMin)
                .param("capacity", capacity).param("lat", lat).param("lng", lng)
                .param("timezone", timezone).param("rule", recurrenceJson)
                .param("startsOn", java.sql.Date.valueOf(startsOn))
                .param("until", until == null ? null : java.sql.Date.valueOf(until))
                .param("createdBy", createdBy).update();
        return id;
    }

    public List<SeriesRow> dueSeries(int limit) {
        return jdbc.sql("""
                        SELECT s.id, s.group_id, s.title, s.description, s.duration_min, s.capacity,
                               ST_Y(COALESCE(s.location, g.location)::geometry) AS lat,
                               ST_X(COALESCE(s.location, g.location)::geometry) AS lng,
                               s.timezone, s.recurrence_rule::text AS recurrence_rule,
                               s.starts_on, s.until_date, s.generated_through, s.status, s.created_by,
                               g.activity_id
                          FROM event_series s
                          JOIN groups g ON g.id = s.group_id
                         WHERE s.status = 'ACTIVE'
                           AND (s.generated_through IS NULL OR s.generated_through < now() + interval '56 days')
                         ORDER BY s.created_at
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query(this::mapSeries)
                .list();
    }

    public int insertOccurrence(SeriesRow series, Instant start, Instant end) {
        return jdbc.sql("""
                        INSERT INTO events (id, series_id, group_id, activity_id, title, location,
                                            start_time, end_time, capacity, confirmed_count, status)
                        VALUES (:id, :seriesId, :groupId, :activityId, :title,
                                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                :startTime, :endTime, :capacity, 0, 'SCHEDULED')
                        ON CONFLICT (series_id, start_time) DO NOTHING
                        """)
                .param("id", UUID.randomUUID()).param("seriesId", series.id())
                .param("groupId", series.groupId()).param("activityId", series.activityId())
                .param("title", series.title()).param("lat", series.lat()).param("lng", series.lng())
                .param("startTime", Timestamp.from(start)).param("endTime", Timestamp.from(end))
                .param("capacity", series.capacity())
                .update();
    }

    public void updateGeneratedThrough(UUID seriesId, Instant horizon) {
        jdbc.sql("UPDATE event_series SET generated_through = :horizon, updated_at = now() WHERE id = :id")
                .param("id", seriesId).param("horizon", Timestamp.from(horizon)).update();
    }

    public RecurrenceEngine.Rule rule(SeriesRow series) {
        try {
            JsonNode node = objectMapper.readTree(series.recurrenceJson());
            String freq = node.path("freq").asText();
            int interval = node.has("interval") ? node.path("interval").asInt() : 1;
            List<DayOfWeek> days = new ArrayList<>();
            if (node.has("byday")) {
                node.path("byday").forEach(day -> days.add(RecurrenceEngine.parseDay(day.asText())));
            }
            LocalTime startTime = LocalTime.parse(node.path("start_time").asText());
            return new RecurrenceEngine.Rule(RecurrenceEngine.Freq.valueOf(freq), interval, days,
                    startTime, series.startsOn(), series.untilDate());
        } catch (Exception e) {
            throw new IllegalStateException("Invalid recurrence rule for series " + series.id(), e);
        }
    }

    public List<EventDtos.OccurrenceResponse> listOccurrences(UUID seriesId) {
        return jdbc.sql("""
                        SELECT id, start_time, end_time FROM events
                         WHERE series_id = :seriesId ORDER BY start_time
                        """)
                .param("seriesId", seriesId)
                .query((rs, rowNum) -> new EventDtos.OccurrenceResponse(
                        rs.getObject("id", UUID.class), rs.getTimestamp("start_time").toInstant(),
                        rs.getTimestamp("end_time").toInstant()))
                .list();
    }

    public List<EventSummary> listEvents(UUID groupId, Instant from, Instant to, int limit, UUID userId) {
        return jdbc.sql("""
                        SELECT e.id, e.series_id, e.title, e.start_time, e.end_time,
                               e.capacity, e.confirmed_count, e.status, r.status AS my_status
                          FROM events e
                          LEFT JOIN rsvps r ON r.event_id = e.id AND r.user_id = :userId
                         WHERE e.group_id = :groupId
                           AND e.start_time >= :fromTime
                           AND e.start_time <= :toTime
                         ORDER BY e.start_time
                         LIMIT :limit
                        """)
                .param("groupId", groupId).param("userId", userId)
                .param("fromTime", Timestamp.from(from)).param("toTime", Timestamp.from(to))
                .param("limit", limit)
                .query((rs, rowNum) -> new EventSummary(
                        rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                        rs.getString("title"), rs.getTimestamp("start_time").toInstant(),
                        rs.getTimestamp("end_time").toInstant(), rs.getInt("capacity"),
                        rs.getInt("confirmed_count"), rs.getString("status"), rs.getString("my_status")))
                .list();
    }

    public Optional<EventDetailResponse> findEvent(UUID eventId, UUID userId) {
        return jdbc.sql("""
                        SELECT e.id, e.series_id, e.title, e.start_time, e.end_time, e.capacity,
                               e.confirmed_count, e.status,
                               ST_Y(e.location::geometry) AS lat, ST_X(e.location::geometry) AS lng,
                               g.id AS group_id, g.name AS group_name, g.visibility,
                               r.status AS my_status,
                               (SELECT count(*) FROM rsvps WHERE event_id = e.id AND status = 'GOING') AS going,
                               (SELECT count(*) FROM rsvps WHERE event_id = e.id AND status = 'MAYBE') AS maybe,
                               (SELECT count(*) FROM rsvps WHERE event_id = e.id AND status = 'DECLINED') AS declined,
                               (SELECT count(*) FROM rsvps WHERE event_id = e.id AND status = 'NOT_GOING') AS not_going
                          FROM events e
                          JOIN groups g ON g.id = e.group_id
                          LEFT JOIN rsvps r ON r.event_id = e.id AND r.user_id = :userId
                         WHERE e.id = :id
                        """)
                .param("id", eventId).param("userId", userId)
                .query((rs, rowNum) -> new EventDetailResponse(
                        rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                        rs.getString("title"), rs.getTimestamp("start_time").toInstant(),
                        rs.getTimestamp("end_time").toInstant(), rs.getInt("capacity"),
                        rs.getInt("confirmed_count"), rs.getString("status"), rs.getString("my_status"),
                        new RsvpCounts(rs.getInt("going"), rs.getInt("maybe"),
                                rs.getInt("declined"), rs.getInt("not_going")),
                        new GroupRef(rs.getObject("group_id", UUID.class), rs.getString("group_name"),
                                rs.getString("visibility")),
                        new LocationResponse(rs.getDouble("lat"), rs.getDouble("lng"))))
                .optional();
    }

    public Optional<EventLock> lockEvent(UUID eventId) {
        return jdbc.sql("""
                        SELECT id, group_id, status, capacity, confirmed_count, start_time
                          FROM events WHERE id = :id FOR UPDATE
                        """)
                .param("id", eventId)
                .query((rs, rowNum) -> new EventLock(
                        rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                        rs.getString("status"), rs.getInt("capacity"), rs.getInt("confirmed_count"),
                        rs.getTimestamp("start_time").toInstant()))
                .optional();
    }

    public Optional<String> rsvpStatus(UUID eventId, UUID userId) {
        return jdbc.sql("SELECT status FROM rsvps WHERE event_id = :eventId AND user_id = :userId")
                .param("eventId", eventId).param("userId", userId)
                .query(String.class).optional();
    }

    public void upsertRsvp(UUID eventId, UUID userId, String status) {
        jdbc.sql("""
                        INSERT INTO rsvps (event_id, user_id, status, version, responded_at, updated_at)
                        VALUES (:eventId, :userId, :status, 1, now(), now())
                        ON CONFLICT (event_id, user_id) DO UPDATE
                        SET status = EXCLUDED.status,
                            version = rsvps.version + 1,
                            responded_at = now(),
                            updated_at = now()
                        """)
                .param("eventId", eventId).param("userId", userId).param("status", status).update();
    }

    public void updateConfirmedCount(UUID eventId, int delta) {
        if (delta == 0) {
            return;
        }
        jdbc.sql("""
                        UPDATE events
                           SET confirmed_count = confirmed_count + :delta,
                               version = version + 1,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", eventId).param("delta", delta).update();
    }

    public Optional<SeriesOwner> seriesOwner(UUID seriesId) {
        return jdbc.sql("""
                        SELECT s.id, s.group_id, s.status, g.status AS group_status
                          FROM event_series s JOIN groups g ON g.id = s.group_id
                         WHERE s.id = :id
                        """)
                .param("id", seriesId)
                .query((rs, rowNum) -> new SeriesOwner(rs.getObject("id", UUID.class),
                        rs.getObject("group_id", UUID.class), rs.getString("status"),
                        rs.getString("group_status")))
                .optional();
    }

    public int cancelSeries(UUID seriesId) {
        int cancelled = jdbc.sql("""
                        UPDATE events SET status = 'CANCELLED', updated_at = now()
                         WHERE series_id = :seriesId AND start_time > now() AND status = 'SCHEDULED'
                        """)
                .param("seriesId", seriesId).update();
        jdbc.sql("UPDATE event_series SET status = 'CANCELLED', updated_at = now() WHERE id = :id")
                .param("id", seriesId).update();
        return cancelled;
    }

    public List<UUID> futureGoingEventIdsForUpdate(UUID groupId, UUID userId) {
        return jdbc.sql("""
                        SELECT e.id
                          FROM events e
                          JOIN rsvps r ON r.event_id = e.id
                         WHERE e.group_id = :groupId AND r.user_id = :userId
                           AND r.status = 'GOING' AND e.start_time > now()
                         ORDER BY e.id
                         FOR UPDATE OF e
                        """)
                .param("groupId", groupId).param("userId", userId)
                .query(UUID.class).list();
    }

    public void declineRsvp(UUID eventId, UUID userId) {
        jdbc.sql("UPDATE rsvps SET status = 'DECLINED', updated_at = now() WHERE event_id = :eventId AND user_id = :userId")
                .param("eventId", eventId).param("userId", userId).update();
    }

    public int decrementConfirmed(UUID eventId) {
        return jdbc.sql("""
                        UPDATE events SET confirmed_count = GREATEST(0, confirmed_count - 1), updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", eventId).update();
    }

    private SeriesRow mapSeries(ResultSet rs, int rowNum) throws SQLException {
        return new SeriesRow(
                rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getString("title"), rs.getString("description"), rs.getInt("duration_min"),
                rs.getInt("capacity"), rs.getDouble("lat"), rs.getDouble("lng"),
                rs.getString("timezone"), rs.getString("recurrence_rule"),
                rs.getDate("starts_on").toLocalDate(),
                rs.getDate("until_date") == null ? null : rs.getDate("until_date").toLocalDate(),
                rs.getTimestamp("generated_through") == null ? null : rs.getTimestamp("generated_through").toInstant(),
                rs.getString("status"), rs.getObject("created_by", UUID.class),
                rs.getObject("activity_id", UUID.class));
    }

    public record SeriesRow(UUID id, UUID groupId, String title, String description, int durationMin,
                            int capacity, double lat, double lng, String timezone, String recurrenceJson,
                            LocalDate startsOn, LocalDate untilDate, Instant generatedThrough,
                            String status, UUID createdBy, UUID activityId) {
    }

    public record EventLock(UUID id, UUID groupId, String status, int capacity, int confirmedCount,
                            Instant startTime) {
    }

    public record SeriesOwner(UUID seriesId, UUID groupId, String seriesStatus, String groupStatus) {
    }
}
