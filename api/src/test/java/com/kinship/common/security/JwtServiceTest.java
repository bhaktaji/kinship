package com.kinship.common.security;

import com.kinship.common.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-123456";

    private JwtService service() {
        return new JwtService(new AppProperties(
                new AppProperties.Jwt(SECRET, "kinship", Duration.ofMinutes(15)),
                new AppProperties.Auth(Duration.ofDays(30), false),
                List.of("http://localhost:3000"),
                new AppProperties.Seed(false), null));
    }

    @Test
    void createsAndVerifiesAccessToken() {
        UUID userId = UUID.randomUUID();
        JwtService jwt = service();
        String token = jwt.createAccessToken(userId);

        var principal = jwt.verify(token);

        assertThat(principal).isPresent();
        assertThat(principal.orElseThrow().id()).isEqualTo(userId);
    }

    @Test
    void rejectsTamperedToken() {
        String token = service().createAccessToken(UUID.randomUUID());
        assertThat(service().verify(token + "x")).isEmpty();
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtService(new AppProperties(
                new AppProperties.Jwt("short", "kinship", Duration.ofMinutes(15)),
                null, List.of(), null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KINSHIP_JWT_SECRET");
    }
}
