package com.kinship.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIT {

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
        registry.add("app.seed.enabled", () -> "false");
    }

    @Autowired
    private TestRestTemplate rest;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void registerLoginRefreshReuseAndLogout() {
        String email = "it-" + System.nanoTime() + "@example.com";
        ResponseEntity<JsonNode> registered = rest.postForEntity("/v1/auth/register",
                body(email), JsonNode.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String accessToken = registered.getBody().get("access_token").asText();
        String refreshCookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(refreshCookie).contains("kinship_rt=");

        ResponseEntity<JsonNode> loggedIn = rest.postForEntity("/v1/auth/login",
                Map.of("email", email, "password", "Demo12345!"), JsonNode.class);
        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(accessToken);
        ResponseEntity<JsonNode> me = rest.exchange("/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(auth), JsonNode.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().get("email").asText()).isEqualTo(email);

        assertThat(getStatus("/v1/users/me")).isEqualTo(401);

        HttpHeaders cookie = new HttpHeaders();
        cookie.add(HttpHeaders.COOKIE, refreshCookie.split(";")[0]);
        ResponseEntity<JsonNode> refreshed = rest.exchange("/v1/auth/refresh", HttpMethod.POST,
                new HttpEntity<>(cookie), JsonNode.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String rotatedCookie = refreshed.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(rotatedCookie).contains("kinship_rt=");

        // Rotation is single use; reusing the old cookie revokes the whole family.
        assertThat(refreshStatus(refreshCookie.split(";")[0])).isEqualTo(401);
        assertThat(refreshStatus(rotatedCookie.split(";")[0])).isEqualTo(401);

        ResponseEntity<JsonNode> loggedInAgain = rest.postForEntity("/v1/auth/login",
                Map.of("email", email, "password", "Demo12345!"), JsonNode.class);
        assertThat(loggedInAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondCookie = loggedInAgain.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        HttpHeaders logoutHeaders = new HttpHeaders();
        logoutHeaders.add(HttpHeaders.COOKIE, secondCookie.split(";")[0]);
        ResponseEntity<Void> loggedOut = rest.exchange("/v1/auth/logout", HttpMethod.POST,
                new HttpEntity<>(logoutHeaders), Void.class);
        assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(refreshStatus(secondCookie.split(";")[0])).isEqualTo(401);
    }

    @Test
    void concurrentRefreshOfTheSameTokenHasExactlyOneWinner() throws Exception {
        String email = "race-" + System.nanoTime() + "@example.com";
        ResponseEntity<JsonNode> registered = rest.postForEntity("/v1/auth/register",
                body(email), JsonNode.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String cookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];

        int attempts = 4;
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.CompletableFuture<Integer>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    HttpRequest request = HttpRequest.newBuilder(
                                    URI.create(rest.getRootUri() + "/v1/auth/refresh"))
                            .header(HttpHeaders.COOKIE, cookie)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                } catch (Exception e) {
                    return -1;
                }
            }));
        }
        start.countDown();
        java.util.List<Integer> statuses = futures.stream()
                .map(java.util.concurrent.CompletableFuture::join).toList();

        // GETDEL makes single-use atomic: exactly one caller can rotate the token.
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
    }

    private int getStatus(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(rest.getRootUri() + path)).GET().build();
        return send(request);
    }

    private int refreshStatus(String cookie) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(rest.getRootUri() + "/v1/auth/refresh"))
                .header(HttpHeaders.COOKIE, cookie)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request);
    }

    private int send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("HTTP request failed", e);
        }
    }

    private Map<String, String> body(String email) {
        return Map.of("email", email, "password", "Demo12345!", "display_name", "Integration User",
                "timezone", "Europe/Berlin");
    }
}
