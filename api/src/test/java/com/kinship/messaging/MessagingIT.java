package com.kinship.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.group.GroupRepository;
import com.kinship.messaging.dto.MessageDtos.SendResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.seed.enabled=false", "app.match.worker.enabled=false"})
class MessagingIT {

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
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private RedisGroupPublisher publisher;
    @Autowired
    private WsTicketService tickets;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentSendsHaveUniqueSequencesAndDuplicateIsIdempotent() throws Exception {
        String owner = register();
        String member = register();
        String groupId = createGroup(owner);
        join(member, groupId);
        try (var pool = Executors.newFixedThreadPool(10)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int index = i;
                futures.add(pool.submit(() -> send(owner, groupId, "m" + index, UUID.randomUUID()).getStatusCode().value()));
            }
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(201);
            }
        }
        List<Long> seqs = jdbc.queryForList("SELECT sequence_id FROM messages WHERE group_id = ?::uuid ORDER BY sequence_id",
                Long.class, groupId);
        assertThat(seqs).hasSize(20);
        assertThat(seqs).isSorted();
        assertThat(seqs.get(19)).isEqualTo(20L);

        UUID clientId = UUID.randomUUID();
        ResponseEntity<JsonNode> first = send(owner, groupId, "dup", clientId);
        ResponseEntity<JsonNode> second = send(owner, groupId, "dup", clientId);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().path("message_id").asText())
                .isEqualTo(first.getBody().path("message_id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE group_id = ?::uuid AND client_msg_id = ?::uuid",
                Integer.class, groupId, clientId)).isEqualTo(1);
    }

    @Test
    void nonMemberCannotReadOrSend() {
        String owner = register();
        String outsider = register();
        String groupId = createGroup(owner);
        ResponseEntity<JsonNode> sent = send(outsider, groupId, "no", UUID.randomUUID());
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> history = rest.exchange("/v1/groups/{id}/messages", HttpMethod.GET,
                new HttpEntity<>(headers(outsider)), JsonNode.class, groupId);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ticketIsSingleUseAndExpiryRemovesIt() {
        String owner = register();
        String groupId = createGroup(owner);
        UUID groupUuid = UUID.fromString(groupId);
        UUID ownerId = userId(owner);

        WsTicketService.Issued issued = tickets.issue(ownerId, groupUuid);
        assertThat(tickets.consume(issued.ticket())).isPresent();
        assertThat(tickets.consume(issued.ticket())).isEmpty();

        WsTicketService.Issued expiring = tickets.issue(ownerId, groupUuid);
        redis.delete(tickets.keyFor(expiring.ticket()));
        assertThat(tickets.consume(expiring.ticket())).isEmpty();

        String outsider = register();
        assertThatThrownBy(() -> tickets.issue(userId(outsider), groupUuid))
                .hasMessageContaining("membership");
    }

    @Test
    void publishRunsOnlyAfterCommit() {
        String owner = register();
        String groupId = createGroup(owner);
        UUID ownerId = userId(owner);
        UUID groupUuid = UUID.fromString(groupId);

        AtomicInteger published = new AtomicInteger();
        RedisGroupPublisher recording = new RedisGroupPublisher(redis, objectMapper) {
            @Override
            public void publish(MessageRepository.MessageRow message) {
                published.incrementAndGet();
            }
        };
        MessageService service = new MessageService(messageRepository, groupRepository, recording,
                org.mockito.Mockito.mock(com.kinship.common.ops.KinshipMetrics.class),
                org.mockito.Mockito.mock(com.kinship.notification.NotificationService.class));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            service.send(ownerId, groupUuid, "rollback-me", UUID.randomUUID());
            status.setRollbackOnly();
        });
        assertThat(published.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE group_id = ?::uuid AND body = 'rollback-me'",
                Integer.class, groupId)).isZero();

        SendResult committed = tx.execute(status ->
                service.send(ownerId, groupUuid, "commit-me", UUID.randomUUID()));
        assertThat(committed).isNotNull();
        assertThat(published.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE group_id = ?::uuid AND body = 'commit-me'",
                Integer.class, groupId)).isEqualTo(1);
    }

    private ResponseEntity<JsonNode> send(String token, String groupId, String body, UUID clientMsgId) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/v1/groups/{id}/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("body", body, "client_msg_id", clientMsgId.toString()), headers),
                JsonNode.class, groupId);
    }

    private void join(String token, String groupId) {
        ResponseEntity<JsonNode> response = rest.exchange("/v1/groups/{id}/join", HttpMethod.POST,
                new HttpEntity<>(headers(token)), JsonNode.class, groupId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createGroup(String token) {
        HttpHeaders headers = headers(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = rest.exchange("/v1/groups", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Chat Group", "category", "sport", "max_members", 4,
                        "visibility", "public", "location", Map.of("lat", 52.52, "lng", 13.405)), headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("group").path("group_id").asText();
    }

    private UUID userId(String token) {
        ResponseEntity<JsonNode> response = rest.exchange("/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(headers(token)), JsonNode.class);
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private String register() {
        String email = "msg-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<JsonNode> response = rest.postForEntity("/v1/auth/register",
                Map.of("email", email, "password", "Demo12345!", "display_name", "Messaging IT",
                        "timezone", "Europe/Berlin"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().path("access_token").asText();
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
