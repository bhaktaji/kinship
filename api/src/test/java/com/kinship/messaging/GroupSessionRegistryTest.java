package com.kinship.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupSessionRegistryTest {

    @Test
    void removedMemberSocketIsClosedAndNeverReceivesTheFrame() throws Exception {
        GroupSessionRegistry registry = new GroupSessionRegistry();
        UUID groupId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID removedId = UUID.randomUUID();
        WebSocketSession active = session();
        WebSocketSession removed = session();
        registry.register(groupId, activeId, active);
        registry.register(groupId, removedId, removed);

        registry.broadcast(groupId, UUID.randomUUID().toString(), "{\"type\":\"msg\"}", Set.of(activeId));

        verify(active).sendMessage(any(TextMessage.class));
        verify(removed, never()).sendMessage(any(TextMessage.class));
        verify(removed).close();
    }

    @Test
    void duplicateFrameWithTheSameMessageIdIsDeliveredOnce() throws Exception {
        GroupSessionRegistry registry = new GroupSessionRegistry();
        UUID groupId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        WebSocketSession session = session();
        registry.register(groupId, userId, session);

        String messageId = UUID.randomUUID().toString();
        registry.broadcast(groupId, messageId, "{\"type\":\"msg\"}", Set.of(userId));
        registry.broadcast(groupId, messageId, "{\"type\":\"msg\"}", Set.of(userId));

        verify(session, times(1)).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
