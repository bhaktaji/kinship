package com.kinship.common.ops;

import com.kinship.messaging.GroupSessionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Business metrics for the P6 dashboards and alerts. HTTP-level latency, pool
 * usage and JVM metrics come from Micrometer's automatic instrumentation; this
 * class only adds the counters and gauges that have no built-in equivalent.
 */
@Component
public class KinshipMetrics {

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;
    private final GroupSessionRegistry sessions;
    private final Counter discoveryHits;
    private final Counter discoveryMisses;
    private final Counter rsvpConflicts;
    private final Counter messagesSent;

    public KinshipMetrics(MeterRegistry registry, JdbcTemplate jdbc, GroupSessionRegistry sessions) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.discoveryHits = Counter.builder("kinship_discovery_requests_total")
                .description("Discovery requests by cache outcome")
                .tag("cache", "hit").register(registry);
        this.discoveryMisses = Counter.builder("kinship_discovery_requests_total")
                .description("Discovery requests by cache outcome")
                .tag("cache", "miss").register(registry);
        this.rsvpConflicts = Counter.builder("kinship_rsvp_conflicts_total")
                .description("RSVP attempts rejected because the event was full").register(registry);
        this.messagesSent = Counter.builder("kinship_messages_sent_total")
                .description("Chat messages committed to the outbox").register(registry);
        Gauge.builder("kinship_match_queue_depth", this, KinshipMetrics::queueDepth)
                .description("Queued plus running match jobs").register(registry);
        Gauge.builder("kinship_ws_connections", this, KinshipMetrics::webSockets)
                .description("Open group WebSocket sessions on this instance").register(registry);
    }

    public void discovery(boolean cacheHit) {
        (cacheHit ? discoveryHits : discoveryMisses).increment();
    }

    public void rsvpConflict() {
        rsvpConflicts.increment();
    }

    public void messageSent() {
        messagesSent.increment();
    }

    public Timer.Sample startMatchJob() {
        return Timer.start(registry);
    }

    public void finishMatchJob(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder("kinship_match_job_duration")
                .description("Wall time to process one match job")
                .tag("outcome", outcome)
                .register(registry));
    }

    private double queueDepth() {
        try {
            Double depth = jdbc.queryForObject(
                    "SELECT count(*) FROM match_jobs WHERE status IN ('QUEUED', 'RUNNING')", Double.class);
            return depth == null ? 0 : depth;
        } catch (RuntimeException e) {
            // A metrics scrape must never fail because the database is unreachable.
            return 0;
        }
    }

    private double webSockets() {
        return sessions.activeConnections();
    }
}
