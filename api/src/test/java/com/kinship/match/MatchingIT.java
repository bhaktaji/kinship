package com.kinship.match;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.seed.enabled=true", "app.match.worker.enabled=false"})
class MatchingIT {

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
    private MatchService matchService;

    @Test
    void proposalHasFourMembersAndAcceptsIntoGroup() throws Exception {
        String owner = register();
        String a = register();
        String b = register();
        String c = register();
        profile(owner, 48.1372, 11.5756, 3, true);
        profile(a, 48.1373, 11.5757, 3, true);
        profile(b, 48.1374, 11.5755, 3, true);
        profile(c, 48.1371, 11.5758, 3, true);

        UUID jobId = request(owner);
        JsonNode job = awaitJob(owner, jobId);
        assertThat(job.path("status").asText()).isEqualTo("DONE");
        JsonNode result = job.path("results").get(0);
        assertThat(result.path("members").size()).isEqualTo(4);
        String matchGroupId = result.path("match_group_id").asText();

        String groupId = null;
        for (String token : List.of(owner, a, b, c)) {
            JsonNode invitation = invitationFor(token, matchGroupId);
            assertThat(invitation).isNotNull();
            ResponseEntity<JsonNode> accepted = rest.exchange(
                    "/v1/match-invitations/{id}/accept", HttpMethod.POST,
                    new HttpEntity<>(headers(token)), JsonNode.class, invitation.path("invitation_id").asText());
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
            if (accepted.getBody().path("group_id").isTextual()) {
                groupId = accepted.getBody().path("group_id").asText();
            }
        }
        assertThat(groupId).isNotNull();
        ResponseEntity<JsonNode> group = rest.exchange("/v1/groups/{id}", HttpMethod.GET,
                new HttpEntity<>(headers(owner)), JsonNode.class, groupId);
        assertThat(group.getBody().path("members").size()).isEqualTo(4);
        assertThat(group.getBody().path("group").path("my_role").asText()).isEqualTo("OWNER");
        assertThat(group.getBody().path("group").path("visibility").asText()).isEqualTo("private");
    }

    @Test
    void excludesFarUsersMissingAvailabilityAndTooFewInterests() throws Exception {
        String owner = register();
        String valid1 = register();
        String valid2 = register();
        String noAvailability = register();
        String far = register();
        profile(owner, 53.5511, 9.9937, 3, true);
        profile(valid1, 53.5512, 9.9938, 3, true);
        profile(valid2, 53.5513, 9.9936, 3, true);
        profile(noAvailability, 53.5514, 9.9939, 3, false);
        profile(far, 48.1372, 11.5756, 3, true);

        UUID jobId = request(owner);
        JsonNode job = awaitJob(owner, jobId);
        assertThat(job.path("status").asText()).isEqualTo("FAILED");
        assertThat(job.path("error_code").asText()).isEqualTo("NO_CANDIDATES");
        assertThat(job.path("results").size()).isZero();
    }

    @Test
    void expiredInvitationCannotBeAccepted() throws Exception {
        String owner = register();
        String a = register();
        String b = register();
        String c = register();
        profile(owner, 50.9375, 6.9603, 3, true);
        profile(a, 50.9376, 6.9604, 3, true);
        profile(b, 50.9377, 6.9602, 3, true);
        profile(c, 50.9374, 6.9605, 3, true);
        UUID jobId = request(owner);
        JsonNode job = awaitJob(owner, jobId);
        String matchGroupId = job.path("results").get(0).path("match_group_id").asText();
        jdbc.update("UPDATE match_groups SET expires_at = now() - interval '1 hour' WHERE id = ?::uuid",
                matchGroupId);
        JsonNode invitation = invitationFor(owner, matchGroupId);
        ResponseEntity<JsonNode> accepted = rest.exchange(
                "/v1/match-invitations/{id}/accept", HttpMethod.POST,
                new HttpEntity<>(headers(owner)), JsonNode.class, invitation.path("invitation_id").asText());
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(accepted.getBody().path("error").path("code").asText()).isEqualTo("INVITATION_EXPIRED");
        matchService.expireDue();
        assertThat(jdbc.queryForObject("SELECT status FROM match_groups WHERE id = ?::uuid",
                String.class, matchGroupId)).isEqualTo("EXPIRED");
    }

    @Test
    void concurrentAcceptsCreateOneGroup() throws Exception {
        String owner = register();
        String a = register();
        String b = register();
        String c = register();
        profile(owner, 48.7758, 9.1829, 3, true);
        profile(a, 48.7759, 9.1830, 3, true);
        profile(b, 48.7760, 9.1828, 3, true);
        profile(c, 48.7757, 9.1831, 3, true);
        UUID jobId = request(owner);
        JsonNode job = awaitJob(owner, jobId);
        String matchGroupId = job.path("results").get(0).path("match_group_id").asText();
        List<String> tokens = List.of(owner, a, b, c);
        List<String> invitationIds = new ArrayList<>();
        for (String token : tokens) {
            invitationIds.add(invitationFor(token, matchGroupId).path("invitation_id").asText());
        }
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String token = tokens.get(i);
                String invitationId = invitationIds.get(i);
                futures.add(pool.submit(() -> rest.exchange("/v1/match-invitations/{id}/accept",
                        HttpMethod.POST, new HttpEntity<>(headers(token)), JsonNode.class, invitationId)
                        .getStatusCode().value()));
            }
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(200);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM match_groups WHERE id = ?::uuid AND status = 'CONFIRMED'",
                Integer.class, matchGroupId)).isEqualTo(1);
        String groupId = jdbc.queryForObject("SELECT group_id::text FROM match_groups WHERE id = ?::uuid",
                String.class, matchGroupId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_members WHERE group_id = ?::uuid AND status = 'ACTIVE'",
                Integer.class, groupId)).isEqualTo(4);
    }

    @Test
    void workerRetryDoesNotDuplicateProposal() throws Exception {
        String owner = register();
        String a = register();
        String b = register();
        String c = register();
        profile(owner, 52.5200, 13.4050, 3, true);
        profile(a, 52.5201, 13.4051, 3, true);
        profile(b, 52.5202, 13.4049, 3, true);
        profile(c, 52.5199, 13.4052, 3, true);
        UUID jobId = request(owner);
        awaitJob(owner, jobId);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM match_groups WHERE job_id = ?::uuid",
                Integer.class, jobId);
        jdbc.update("UPDATE match_jobs SET status = 'QUEUED', started_at = NULL, completed_at = NULL WHERE id = ?::uuid",
                jobId);
        awaitJob(owner, jobId);
        Integer after = jdbc.queryForObject("SELECT count(*) FROM match_groups WHERE job_id = ?::uuid",
                Integer.class, jobId);
        assertThat(after).isEqualTo(before).isEqualTo(1);
    }

    @Test
    void declineBelowQuorumExpiresAndRequeuesAcceptedUsers() {
        String owner = register();
        String a = register();
        String b = register();
        String c = register();
        profile(owner, 49.0069, 8.4037, 3, true);
        profile(a, 49.0070, 8.4038, 3, true);
        profile(b, 49.0071, 8.4036, 3, true);
        profile(c, 49.0068, 8.4039, 3, true);
        UUID jobId = request(owner);
        JsonNode job;
        try {
            job = awaitJob(owner, jobId);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        String matchGroupId = job.path("results").get(0).path("match_group_id").asText();
        for (String token : List.of(owner, a, b)) {
            JsonNode invitation = invitationFor(token, matchGroupId);
            ResponseEntity<JsonNode> accepted = rest.exchange("/v1/match-invitations/{id}/accept",
                    HttpMethod.POST, new HttpEntity<>(headers(token)), JsonNode.class,
                    invitation.path("invitation_id").asText());
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        JsonNode declining = invitationFor(c, matchGroupId);
        ResponseEntity<JsonNode> declined = rest.exchange("/v1/match-invitations/{id}/decline",
                HttpMethod.POST, new HttpEntity<>(headers(c)), JsonNode.class,
                declining.path("invitation_id").asText());
        assertThat(declined.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM match_groups WHERE id = ?::uuid",
                String.class, matchGroupId)).isEqualTo("EXPIRED");
        String acceptedIds = String.join(",", List.of(userId(owner), userId(a), userId(b)).stream()
                .map(UUID::toString).toList());
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM match_jobs
                         WHERE status = 'QUEUED' AND user_id = ANY(string_to_array(?, ',')::uuid[])
                        """, Integer.class, acceptedIds)).isEqualTo(3);
    }

    private UUID request(String token) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.exchange("/v1/match-requests", HttpMethod.POST,
                new HttpEntity<>(Map.of("max_distance_km", 10, "preferred_group_size", 4), headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(response.getBody().path("job_id").asText());
    }

    private JsonNode awaitJob(String token, UUID jobId) throws Exception {
        for (int i = 0; i < 20; i++) {
            matchService.processNextJob();
            ResponseEntity<JsonNode> response = rest.exchange("/v1/match-requests/{id}", HttpMethod.GET,
                    new HttpEntity<>(headers(token)), JsonNode.class, jobId);
            JsonNode body = response.getBody();
            if ("DONE".equals(body.path("status").asText()) || "FAILED".equals(body.path("status").asText())) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Match job did not finish: " + jobId);
    }

    private JsonNode invitationFor(String token, String matchGroupId) {
        ResponseEntity<JsonNode> response = rest.exchange("/v1/match-invitations?status=INVITED", HttpMethod.GET,
                new HttpEntity<>(headers(token)), JsonNode.class);
        for (JsonNode item : response.getBody().path("items")) {
            if (matchGroupId.equals(item.path("match_group_id").asText())) {
                return item;
            }
        }
        return null;
    }

    private void profile(String token, double lat, double lng, int interestCount, boolean availability) {
        List<Integer> ids = jdbc.queryForList("SELECT id FROM interests ORDER BY id LIMIT ?", Integer.class, interestCount);
        List<Map<String, Object>> interests = ids.stream()
                .map(id -> Map.<String, Object>of("interest_id", id, "weight", 1.0)).toList();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("location", Map.of("lat", lat, "lng", lng));
        body.put("interests", interests);
        if (availability) {
            body.put("availability", List.of(Map.of("day_of_week", 4,
                    "start_local", "18:00", "end_local", "21:00")));
        }
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.exchange("/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String register() {
        String email = "p3-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<JsonNode> response = rest.postForEntity("/v1/auth/register",
                Map.of("email", email, "password", "Demo12345!", "display_name", "P3 IT",
                        "timezone", "Europe/Berlin"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("access_token").asText();
    }

    private UUID userId(String token) {
        ResponseEntity<JsonNode> response = rest.exchange("/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(headers(token)), JsonNode.class);
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
