package com.kinship.activity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class H3ServiceTest {

    private final H3Service h3 = new H3Service();

    @Test
    void computesStableResolutionSevenCell() {
        String berlin = h3.cell(52.5200, 13.4050);
        assertThat(berlin).hasSize(15);
        assertThat(h3.cell(52.5200, 13.4050)).isEqualTo(berlin);
        assertThat(h3.cell(48.1372, 11.5756)).isNotEqualTo(berlin);
    }
}
