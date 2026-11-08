package com.kinship.common.ops;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CountReconciliationJobTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void repairsEveryDriftedEventToTheRsvpTruth() {
        when(jdbc.queryForList(CountReconciliationJob.DRIFT_QUERY)).thenReturn(List.of(
                Map.of("event_id", "11111111-1111-1111-1111-111111111111", "stored", 3, "actual", 2L),
                Map.of("event_id", "22222222-2222-2222-2222-222222222222", "stored", 0, "actual", 4L)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        int repaired = new CountReconciliationJob(jdbc, true).reconcile();

        assertThat(repaired).isEqualTo(2);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), args.capture());
        assertThat(args.getAllValues())
                .extracting(values -> ((Number) values[0]).intValue())
                .containsExactly(2, 4);
        assertThat(args.getAllValues())
                .extracting(values -> values[1])
                .containsExactly("11111111-1111-1111-1111-111111111111",
                        "22222222-2222-2222-2222-222222222222");
    }

    @Test
    void doesNothingWhenCountsAgree() {
        when(jdbc.queryForList(CountReconciliationJob.DRIFT_QUERY)).thenReturn(List.of());

        int repaired = new CountReconciliationJob(jdbc, true).reconcile();

        assertThat(repaired).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
