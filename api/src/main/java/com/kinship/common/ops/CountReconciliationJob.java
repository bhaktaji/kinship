package com.kinship.common.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Nightly repair of the denormalised {@code events.confirmed_count}. The RSVP
 * transaction keeps it correct, so any drift here means a bug or a manual edit;
 * the job logs every mismatch before repairing it, which is the alarm signal.
 */
@Component
public class CountReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(CountReconciliationJob.class);

    static final String DRIFT_QUERY = """
            SELECT e.id::text AS event_id,
                   e.confirmed_count AS stored,
                   count(r.user_id) FILTER (WHERE r.status = 'GOING') AS actual
              FROM events e
              LEFT JOIN rsvps r ON r.event_id = e.id
             GROUP BY e.id, e.confirmed_count
            HAVING e.confirmed_count <> count(r.user_id) FILTER (WHERE r.status = 'GOING')
            """;

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public CountReconciliationJob(JdbcTemplate jdbc,
                                  @Value("${app.reconcile.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.reconcile.cron:0 30 3 * * *}")
    public void scheduledRun() {
        if (!enabled) {
            return;
        }
        int repaired = reconcile();
        if (repaired > 0) {
            log.warn("rsvp_count_drift_repaired events={}", repaired);
        } else {
            log.info("rsvp_count_reconcile clean=true");
        }
    }

    @Transactional
    public int reconcile() {
        List<Map<String, Object>> rows = jdbc.queryForList(DRIFT_QUERY);
        int repaired = 0;
        for (Map<String, Object> row : rows) {
            String eventId = String.valueOf(row.get("event_id"));
            Object stored = row.get("stored");
            Object actual = row.get("actual");
            log.warn("rsvp_count_drift event_id={} stored={} actual={}", eventId, stored, actual);
            repaired += jdbc.update(
                    "UPDATE events SET confirmed_count = ?, updated_at = now() WHERE id = ?::uuid",
                    ((Number) actual).intValue(), eventId);
        }
        return repaired;
    }
}
