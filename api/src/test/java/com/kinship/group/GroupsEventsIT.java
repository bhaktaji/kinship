package com.kinship.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.kinship.event.EventRepository;
import com.kinship.event.OccurrenceGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.seed.enabled=true")
class GroupsEventsIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("kinship").withUsername("kinship").withPassword("kinship");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.jwt.secret", () -> "integration-secret-integration-secret-1234");
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private OccurrenceGenerator generator;

    @Test
    void occurrenceGenerationIsIdempotent() {
        String token = register();
        String groupId = createGroup(token, "Idempotent Group", 6);
        LocalDate start = LocalDate.now(ZoneId.of("Europe/Berlin")).plusDays(2);
        String seriesId = createSeries(token, groupId, "Idempotent Series", start, 6);
        String before = seriesId;
        UUID id = UUID.fromString(seriesId);
        EventRepository.SeriesRow series = eventRepository.findSeries(id).orElseThrow();
        int first = generator.extendSeries(series, Instant.now());
        int second = generator.extendSeries(eventRepository.findSeries(id).orElseThrow(), Instant.now());
        assertThat(first).isGreaterThanOrEqualTo(0);
        assertThat(second).isZero();
        Integer count = jdbc.queryForObject("SELECT count(*) FROM events WHERE series_id = ?", Integer.class, id);
        assertThat(count).isGreaterThan(0);
        assertThat(before).isEqualTo(seriesId);
    }

    @Test
    void joinCapacityIsTransactional() throws Exception {
        String owner = register();
        String groupId = createGroup(owner, "Join Race Group", 2);
        String a = register();
        String b = register();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = List.of(
                    pool.submit(joinCall(groupId, a)),
                    pool.submit(joinCall(groupId, b)));
            List<Integer> statuses = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM group_members WHERE group_id = ?::uuid AND status = 'ACTIVE'",
                Integer.class, groupId);
        assertThat(active).isEqualTo(2);
    }

    @Test
    void rsvpCapacityIsTransactional() throws Exception {
        String owner = register();
        String groupId = createGroup(owner, "RSVP Race Group", 2);
        String member = register();
        joinCall(groupId, member).call();
        LocalDate start = LocalDate.now(ZoneId.of("Europe/Berlin")).plusDays(2);
        String seriesId = createSeries(tokenFor(owner), groupId, "RSVP Race Series", start, 1);
        String eventId = jdbc.queryForObject(
                "SELECT id::text FROM events WHERE series_id = ?::uuid ORDER BY start_time LIMIT 1",
                String.class, seriesId);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = List.of(
                    pool.submit(rsvpCall(eventId, owner)),
                    pool.submit(rsvpCall(eventId, member)));
            List<Integer> statuses = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        }
        Integer confirmed = jdbc.queryForObject(
                "SELECT confirmed_count FROM events WHERE id = ?::uuid", Integer.class, eventId);
        assertThat(confirmed).isEqualTo(1);
    }

    @Test
    void leavingCancelsFutureGoingRsvps() throws Exception {
        String owner = register();
        String groupId = createGroup(owner, "Leave Group", 2);
        String member = register();
        joinCall(groupId, member).call();
        LocalDate start = LocalDate.now(ZoneId.of("Europe/Berlin")).plusDays(2);
        String seriesId = createSeries(owner, groupId, "Leave Series", start, 2);
        String eventId = jdbc.queryForObject(
                "SELECT id::text FROM events WHERE series_id = ?::uuid ORDER BY start_time LIMIT 1",
                String.class, seriesId);
        assertThat(rsvpCall(eventId, member).call()).isEqualTo(200);
        HttpHeaders headers = headers(member);
        ResponseEntity<Void> leave = rest.exchange("/v1/groups/{id}/members/me", HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class, groupId);
        assertThat(leave.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT confirmed_count FROM events WHERE id = ?::uuid",
                Integer.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM rsvps WHERE event_id = ?::uuid AND user_id = ?::uuid",
                String.class, eventId, userId(member))).isEqualTo("DECLINED");
    }

    private Callable<Integer> joinCall(String groupId, String token) {
        return () -> rest.exchange("/v1/groups/{id}/join", HttpMethod.POST,
                new HttpEntity<>(headers(token)), JsonNode.class, groupId).getStatusCode().value();
    }

    private Callable<Integer> rsvpCall(String eventId, String token) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return () -> rest.exchange("/v1/events/{id}/rsvp", HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "GOING"), headers), JsonNode.class, eventId)
                .getStatusCode().value();
    }

    private String createGroup(String token, String name, int maxMembers) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "name", name, "category", "sport", "max_members", maxMembers,
                "visibility", "public", "location", Map.of("lat", 52.52, "lng", 13.405));
        ResponseEntity<JsonNode> response = rest.exchange("/v1/groups", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("group").path("group_id").asText();
    }

    private String createSeries(String token, String groupId, String title, LocalDate start, int capacity) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String day = start.getDayOfWeek().name().substring(0, 3);
        Map<String, Object> body = Map.of(
                "title", title, "capacity", capacity, "timezone", "Europe/Berlin",
                "recurrence", Map.of("freq", "WEEKLY", "interval", 1, "byday", List.of(day),
                        "start_time", "18:30", "starts_on", start.toString()));
        ResponseEntity<JsonNode> response = rest.exchange("/v1/groups/{id}/events", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class, groupId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("event_series_id").asText();
    }

    private String register() {
        String email = "p2-" + System.nanoTime() + "@example.com";
        ResponseEntity<JsonNode> response = rest.postForEntity("/v1/auth/register",
                Map.of("email", email, "password", "Demo12345!", "display_name", "P2 IT",
                        "timezone", "Europe/Berlin"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("access_token").asText();
    }

    private String tokenFor(String token) {
        return token;
    }

    private UUID userId(String token) {
        HttpHeaders headers = headers(token);
        ResponseEntity<JsonNode> response = rest.exchange("/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
