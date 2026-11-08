package com.kinship.messaging;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class TicketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ERROR = "ws.error";
    public static final String USER_ID = "ws.userId";
    public static final String GROUP_ID = "ws.groupId";

    private final WsTicketService tickets;

    public TicketHandshakeInterceptor(WsTicketService tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        var consumed = tickets.consume(ticket);
        if (consumed.isEmpty()) {
            attributes.put(ERROR, 4401);
            return true;
        }
        attributes.put(USER_ID, consumed.get().userId());
        attributes.put(GROUP_ID, consumed.get().groupId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No-op.
    }
}
