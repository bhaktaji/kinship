package com.kinship.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.activity.H3Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Reproducible demo city seed. Disabled by default; enable with KINSHIP_SEED=true.
 * Deterministic UUIDs and a fixed random seed make repeated runs idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final double BASE_LAT = 52.5200;
    private static final double BASE_LNG = 13.4050;

    private static final Map<String, List<String>> ACTIVITY_NAMES = new LinkedHashMap<>();
    static {
        ACTIVITY_NAMES.put("sport", List.of("Bouldering", "Running Club", "Football", "Yoga in the Park"));
        ACTIVITY_NAMES.put("music", List.of("Live Music Night", "Vinyl Listening", "Choir Practice", "Guitar Circle"));
        ACTIVITY_NAMES.put("outdoors", List.of("City Hike", "Cycling Loop", "Photo Walk", "Camping Weekend"));
    }

    private static final List<String> CATEGORIES = List.of("sport", "music", "outdoors");

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final H3Service h3;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public DemoDataSeeder(JdbcClient jdbc, PasswordEncoder passwordEncoder, H3Service h3,
                          ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.h3 = h3;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.sql("SELECT count(*) FROM activities").query(Integer.class).single();
        if (existing != null && existing > 0) {
            log.info("seed_skipped existing_activities={}", existing);
            return;
        }
        transaction.executeWithoutResult(status -> seed());
    }

    private void seed() {
        UUID demoUserId = uuid("kinship-demo-user");
        seedDemoUser(demoUserId);
        Map<String, Integer> interests = seedInterests();
        seedDemoInterests(demoUserId, interests);
        List<UUID> organizations = seedOrganizations();
        List<SeedActivity> activities = seedActivities(organizations);
        List<UUID> groups = seedGroups(demoUserId, activities);
        seedSeries(demoUserId, activities, groups);
        log.info("seed_complete activities={} groups={} series={} interests={}",
                activities.size(), groups.size(), 5, interests.size());
    }

    private void seedDemoUser(UUID userId) {
        double lat = BASE_LAT;
        double lng = BASE_LNG;
        String availability = "[{\"day_of_week\":4,\"start_local\":\"18:00\",\"end_local\":\"21:00\"}]";
        jdbc.sql("""
                        INSERT INTO users (id, email, password_hash, display_name, timezone, location, h3_cell_r7, availability)
                        VALUES (:id, :email, :hash, 'Demo Organizer', 'Europe/Berlin',
                                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :h3,
                                CAST(:availability AS jsonb))
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", userId)
                .param("email", "demo@kinship.local")
                .param("hash", passwordEncoder.encode("Demo12345!"))
                .param("lat", lat)
                .param("lng", lng)
                .param("h3", h3.cell(lat, lng))
                .param("availability", availability)
                .update();
    }

    private Map<String, Integer> seedInterests() {
        List<String[]> rows = List.of(
                new String[]{"bouldering", "Bouldering", "sport"},
                new String[]{"running", "Running", "sport"},
                new String[]{"football", "Football", "sport"},
                new String[]{"yoga", "Yoga", "sport"},
                new String[]{"live-music", "Live Music", "music"},
                new String[]{"vinyl", "Vinyl", "music"},
                new String[]{"choir", "Choir", "music"},
                new String[]{"guitar", "Guitar", "music"},
                new String[]{"hiking", "Hiking", "outdoors"},
                new String[]{"cycling", "Cycling", "outdoors"},
                new String[]{"photography", "Photography", "outdoors"},
                new String[]{"camping", "Camping", "outdoors"});
        for (String[] row : rows) {
            jdbc.sql("""
                            INSERT INTO interests (slug, name, category)
                            VALUES (:slug, :name, :category)
                            ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, category = EXCLUDED.category
                            """)
                    .param("slug", row[0]).param("name", row[1]).param("category", row[2]).update();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbc.sql("SELECT id, slug FROM interests")
                .query((rs, rowNum) -> Map.entry(rs.getString("slug"), rs.getInt("id")))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private void seedDemoInterests(UUID userId, Map<String, Integer> interests) {
        for (String slug : List.of("bouldering", "live-music", "hiking")) {
            jdbc.sql("""
                            INSERT INTO user_interests (user_id, interest_id, weight)
                            VALUES (:userId, :interestId, 1.0)
                            ON CONFLICT (user_id, interest_id) DO NOTHING
                            """)
                    .param("userId", userId)
                    .param("interestId", interests.get(slug))
                    .update();
        }
    }

    private List<UUID> seedOrganizations() {
        List<String> names = List.of("Kinship Collective", "Berlin Sport Hub", "Neighbourhood Arts");
        List<UUID> ids = java.util.stream.IntStream.range(0, names.size())
                .mapToObj(i -> uuid("organization-" + i)).toList();
        for (int i = 0; i < names.size(); i++) {
            jdbc.sql("""
                            INSERT INTO organizations (id, name, type, location)
                            VALUES (:id, :name, 'community',
                                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
                            ON CONFLICT (id) DO NOTHING
                            """)
                    .param("id", ids.get(i)).param("name", names.get(i))
                    .param("lat", BASE_LAT).param("lng", BASE_LNG).update();
        }
        return ids;
    }

    private List<SeedActivity> seedActivities(List<UUID> organizations) {
        Random random = new Random(42);
        List<SeedActivity> activities = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String category = CATEGORIES.get(i % CATEGORIES.size());
            List<String> names = ACTIVITY_NAMES.get(category);
            String title = names.get((i / CATEGORIES.size()) % names.size()) + " #" + (i + 1);
            double lat = BASE_LAT + (random.nextDouble() - 0.5) * 0.10;
            double lng = BASE_LNG + (random.nextDouble() - 0.5) * 0.14;
            UUID id = uuid("activity-" + i);
            String description = "A friendly " + category + " meetup for small recurring groups in Berlin.";
            jdbc.sql("""
                            INSERT INTO activities (id, title, description, category, location, address,
                                                    h3_cell_r7, organization_id, image_url)
                            VALUES (:id, :title, :description, :category,
                                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                    :address, :h3, :orgId, NULL)
                            ON CONFLICT (id) DO NOTHING
                            """)
                    .param("id", id).param("title", title).param("description", description)
                    .param("category", category).param("lat", lat).param("lng", lng)
                    .param("address", "Demo address " + (i + 1) + ", Berlin")
                    .param("h3", h3.cell(lat, lng))
                    .param("orgId", organizations.get(i % organizations.size())).update();
            activities.add(new SeedActivity(id, title, category, lat, lng));
        }
        return activities;
    }

    private List<UUID> seedGroups(UUID ownerId, List<SeedActivity> activities) {
        List<UUID> groups = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            SeedActivity activity = activities.get(i);
            UUID groupId = uuid("group-" + i);
            jdbc.sql("""
                            INSERT INTO groups (id, name, description, category, activity_id, location,
                                                max_members, visibility, status, created_by)
                            VALUES (:id, :name, :description, :category, :activityId,
                                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                    6, 'public', 'ACTIVE', :ownerId)
                            ON CONFLICT (id) DO NOTHING
                            """)
                    .param("id", groupId)
                    .param("name", activity.title() + " Crew")
                    .param("description", "A small recurring group for " + activity.title() + ".")
                    .param("category", activity.category())
                    .param("activityId", activity.id())
                    .param("lat", activity.lat()).param("lng", activity.lng())
                    .param("ownerId", ownerId)
                    .update();
            jdbc.sql("""
                            INSERT INTO group_members (group_id, user_id, role, status)
                            VALUES (:groupId, :userId, 'OWNER', 'ACTIVE')
                            ON CONFLICT (group_id, user_id) DO UPDATE SET role = 'OWNER', status = 'ACTIVE'
                            """)
                    .param("groupId", groupId).param("userId", ownerId).update();
            groups.add(groupId);
        }
        return groups;
    }

    private void seedSeries(UUID creatorId, List<SeedActivity> activities, List<UUID> groups) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate startsOn = today.with(TemporalAdjusters.next(DayOfWeek.THURSDAY));
        Instant generatedThrough = Instant.now().plus(Duration.ofDays(56));
        for (int i = 0; i < 5; i++) {
            SeedActivity activity = activities.get(i);
            UUID groupId = groups.get(i);
            UUID seriesId = uuid("series-" + i);
            String rule = writeRule();
            jdbc.sql("""
                            INSERT INTO event_series (id, group_id, title, description, duration_min, capacity,
                                                      location, timezone, recurrence_rule, starts_on, until_date,
                                                      generated_through, status, created_by)
                            VALUES (:id, :groupId, :title, :description, 90, 6,
                                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                    'Europe/Berlin', CAST(:rule AS jsonb), :startsOn, :until,
                                    :generatedThrough, 'ACTIVE', :creatorId)
                            ON CONFLICT (id) DO NOTHING
                            """)
                    .param("id", seriesId).param("groupId", groupId)
                    .param("title", activity.title()).param("description", "Weekly recurring meetup.")
                    .param("lat", activity.lat()).param("lng", activity.lng())
                    .param("rule", rule).param("startsOn", Date.valueOf(startsOn))
                    .param("until", Date.valueOf(startsOn.plusMonths(6)))
                    .param("generatedThrough", Timestamp.from(generatedThrough))
                    .param("creatorId", creatorId).update();
            for (int week = 0; week < 8; week++) {
                LocalDate occurrenceDate = startsOn.plusWeeks(week);
                Instant start = ZonedDateTime.of(occurrenceDate, LocalTime.of(18, 30), ZONE).toInstant();
                Instant end = start.plus(Duration.ofMinutes(90));
                jdbc.sql("""
                                INSERT INTO events (id, series_id, group_id, activity_id, title, location,
                                                    start_time, end_time, capacity, confirmed_count, status)
                                VALUES (:id, :seriesId, :groupId, :activityId, :title,
                                        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                        :startTime, :endTime, 6, 0, 'SCHEDULED')
                                ON CONFLICT (series_id, start_time) DO NOTHING
                                """)
                        .param("id", uuid("event-" + i + "-" + week))
                        .param("seriesId", seriesId).param("groupId", groupId)
                        .param("activityId", activity.id()).param("title", activity.title())
                        .param("lat", activity.lat()).param("lng", activity.lng())
                        .param("startTime", Timestamp.from(start)).param("endTime", Timestamp.from(end))
                        .update();
            }
        }
    }

    private String writeRule() {
        try {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("freq", "WEEKLY");
            rule.put("interval", 1);
            rule.put("byday", List.of("THU"));
            rule.put("start_time", "18:30");
            return objectMapper.writeValueAsString(rule);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build recurrence rule", e);
        }
    }

    private UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private record SeedActivity(UUID id, String title, String category, double lat, double lng) {
    }
}
