package com.kinship.match;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "app.match.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MatchWorker {

    private static final Logger log = LoggerFactory.getLogger(MatchWorker.class);
    private final MatchService matchService;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public MatchWorker(MatchService matchService) {
        this.matchService = matchService;
    }

    @Scheduled(fixedDelay = 10_000)
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            while (matchService.processNextJob()) {
                // Drain queued jobs one at a time; the claim is SKIP LOCKED.
            }
        } finally {
            polling.set(false);
        }
    }

    @Scheduled(fixedDelay = 600_000)
    public void expire() {
        int expired = matchService.expireDue();
        int recovered = matchService.recoverStale();
        if (expired > 0 || recovered > 0) {
            log.info("match_sweep expired={} recovered={}", expired, recovered);
        }
    }
}
