package com.kinship.messaging;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WsHeartbeat {

    private final GroupSessionRegistry registry;

    public WsHeartbeat(GroupSessionRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelay = 30_000)
    public void ping() {
        registry.pingAll();
    }
}
