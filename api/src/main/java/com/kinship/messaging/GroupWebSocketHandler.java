package com.kinship.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.common.api.ApiException;
import com.kinship.messaging.dto.MessageDtos.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class GroupWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupWebSocketHandler.class);

    private final GroupSessionRegistry registry;
    private final RedisGroupFanout fanout;
    private final MessageService messages;
    private final ObjectMapper objectMapper;

    public GroupWebSocketHandler(GroupSessionRegistry registry, RedisGroupFanout fanout,
                                 MessageService messages, ObjectMapper objectMapper) {
        this.registry = registry;
        this.fanout = fanout;
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Integer error = (Integer) session.getAttributes().get(TicketHandshakeInterceptor.ERROR);
        if (error != null) {
            session.close(new CloseStatus(error));
            return;
        }
        UUID userId = (UUID) session.getAttributes().get(TicketHandshakeInterceptor.USER_ID);
        UUID groupId = (UUID) session.getAttributes().get(TicketHandshakeInterceptor.GROUP_ID);
        registry.register(groupId, userId, session);
        fanout.subscribe(groupId);
        send(session, Map.of("type", "ready", "group_id", groupId.toString(),
                "last_sequence_id", messages.latestSequence(userId, groupId)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UUID userId = (UUID) session.getAttributes().get(TicketHandshakeInterceptor.USER_ID);
        UUID groupId = (UUID) session.getAttributes().get(TicketHandshakeInterceptor.GROUP_ID);
        String clientMsgId = null;
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText();
            if ("ping".equals(type)) {
                send(session, Map.of("type", "pong"));
                return;
            }
            if (!"msg".equals(type)) {
                send(session, Map.of("type", "error", "code", "UNSUPPORTED_FRAME"));
                return;
            }
            String body = node.path("body").asText("");
            clientMsgId = node.path("client_msg_id").asText(null);
            UUID parsedClientId;
            try {
                parsedClientId = parseUuid(clientMsgId);
            } catch (IllegalArgumentException e) {
                send(session, Map.of("type", "error", "code", "VALIDATION_ERROR",
                        "client_msg_id", String.valueOf(clientMsgId)));
                return;
            }
            SendResult result = messages.send(userId, groupId, body, parsedClientId);
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("type", "ack");
            ack.put("client_msg_id", clientMsgId);
            ack.put("message_id", result.response().messageId().toString());
            ack.put("sequence_id", result.response().sequenceId());
            ack.put("created_at", result.response().createdAt().toString());
            send(session, ack);
        } catch (ApiException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", "error");
            error.put("code", e.code());
            if (clientMsgId != null) {
                error.put("client_msg_id", clientMsgId);
            }
            send(session, error);
        } catch (RuntimeException e) {
            log.warn("ws_send_failed session_id={} reason={}", session.getId(), e.getClass().getSimpleName());
            send(session, Map.of("type", "error", "code", "INTERNAL"));
        } catch (Exception e) {
            send(session, Map.of("type", "error", "code", "INTERNAL"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID groupId = (UUID) session.getAttributes().get(TicketHandshakeInterceptor.GROUP_ID);
        if (groupId != null) {
            registry.unregister(groupId, session.getId());
            fanout.unsubscribeIfEmpty(groupId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        session.close(CloseStatus.SERVER_ERROR);
    }

    private UUID parseUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private void send(WebSocketSession session, Map<String, ?> payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)));
                }
            }
        } catch (Exception e) {
            log.debug("ws_write_failed session_id={}", session.getId());
        }
    }
}
