package com.kinship.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinship.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec(new ObjectMapper());

    @Test
    void roundTripsCursor() {
        UUID activityId = UUID.randomUUID();
        String fingerprint = codec.fingerprint(52.52, 13.405, 10, null, null, null);
        String token = codec.encode(fingerprint, 1234, activityId);

        CursorCodec.Cursor cursor = codec.decode(token, fingerprint);

        assertThat(cursor.activityId()).isEqualTo(activityId);
        assertThat(cursor.distanceM()).isEqualTo(1234);
    }

    @Test
    void rejectsFingerprintMismatch() {
        String token = codec.encode(codec.fingerprint(52.52, 13.405, 10, null, null, null),
                10, UUID.randomUUID());
        assertThatThrownBy(() -> codec.decode(token,
                codec.fingerprint(52.52, 13.405, 5, null, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> codec.decode("not-a-cursor", "f"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void fingerprintChangesWhenFiltersChange() {
        String base = codec.fingerprint(52.52, 13.405, 10, null, null, null);
        assertThat(codec.fingerprint(52.52, 13.405, 10, "sport", null, null)).isNotEqualTo(base);
        assertThat(codec.fingerprint(52.52, 13.405, 10, null, Instant.now(), null)).isNotEqualTo(base);
        assertThat(codec.fingerprint(52.52, 13.405, 10, null, null, 6)).isNotEqualTo(base);
    }
}
