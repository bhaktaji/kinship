package com.kinship.messaging;

import com.kinship.common.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.List;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GroupWebSocketHandler handler;
    private final TicketHandshakeInterceptor interceptor;
    private final AppProperties properties;

    public WebSocketConfig(GroupWebSocketHandler handler, TicketHandshakeInterceptor interceptor,
                           AppProperties properties) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> origins = properties.corsOrigins();
        registry.addHandler(handler, "/v1/ws")
                .addInterceptors(interceptor)
                .setAllowedOrigins(origins == null || origins.isEmpty()
                        ? new String[]{"http://localhost:3000"} : origins.toArray(String[]::new));
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8192);
        container.setMaxBinaryMessageBufferSize(8192);
        return container;
    }
}
