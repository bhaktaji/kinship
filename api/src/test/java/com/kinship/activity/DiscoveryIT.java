package com.kinship.activity;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.seed.enabled=true")
class DiscoveryIT {

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

    @Test
    void seedsExpectedShape() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activities", Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM groups", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_series", Integer.class)).isEqualTo(5);
    }

    @Test
    void cachesDiscoveryAndPaginatesWithCursor() {
        String token = register();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<JsonNode> first = rest.exchange(
                "/v1/activities?lat=52.5200&lng=13.4050&radius_km=10&limit=2",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getFirst("X-Cache")).isEqualTo("MISS");
        assertThat(first.getBody().get("items")).hasSize(2);
        String nextToken = first.getBody().get("next_page_token").asText();

        ResponseEntity<JsonNode> second = rest.exchange(
                "/v1/activities?lat=52.5200&lng=13.4050&radius_km=10&limit=2&page_token=" + nextToken,
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("X-Cache")).isEqualTo("HIT");

        List<String> ids = new ArrayList<>();
        first.getBody().get("items").forEach(item -> ids.add(item.get("activity_id").asText()));
        second.getBody().get("items").forEach(item -> ids.add(item.get("activity_id").asText()));
        assertThat(ids).doesNotHaveDuplicates();
    }

    private String register() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/v1/auth/register",
                Map.of("email", "discovery-" + System.nanoTime() + "@example.com",
                        "password", "Demo12345!", "display_name", "Discovery User",
                        "timezone", "Europe/Berlin"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("access_token").asText();
    }
}
