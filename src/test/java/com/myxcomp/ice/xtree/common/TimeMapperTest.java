package com.myxcomp.ice.xtree.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class TimeMapperTest {

    private TimeMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TimeMapper();
    }

    @Test
    void toLocalDateTime_converts_utc_instant_to_local_datetime() {
        Instant instant = Instant.parse("2026-05-13T14:30:00Z");
        LocalDateTime result = mapper.toLocalDateTime(instant);
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 5, 13, 14, 30, 0));
    }

    @Test
    void toInstant_treats_local_datetime_as_utc() {
        LocalDateTime ldt = LocalDateTime.of(2026, 5, 13, 14, 30, 0);
        Instant result = mapper.toInstant(ldt);
        assertThat(result).isEqualTo(Instant.parse("2026-05-13T14:30:00Z"));
    }

    @Test
    void roundTrip_instant_to_localDateTime_and_back() {
        Instant original = Instant.parse("2026-05-13T14:30:00.123456789Z");
        assertThat(mapper.toInstant(mapper.toLocalDateTime(original))).isEqualTo(original);
    }

    @Test
    void toLocalDateTime_is_utc_regardless_of_jvm_timezone() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")); // UTC-5
            Instant instant = Instant.parse("2026-05-13T14:30:00Z");
            assertThat(mapper.toLocalDateTime(instant))
                    .isEqualTo(LocalDateTime.of(2026, 5, 13, 14, 30, 0));
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void toInstant_interprets_as_utc_regardless_of_jvm_timezone() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo")); // UTC+9
            LocalDateTime ldt = LocalDateTime.of(2026, 5, 13, 14, 30, 0);
            assertThat(mapper.toInstant(ldt))
                    .isEqualTo(Instant.parse("2026-05-13T14:30:00Z"));
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void toLocalDateTime_handles_epoch() {
        assertThat(mapper.toLocalDateTime(Instant.EPOCH))
                .isEqualTo(LocalDateTime.of(1970, 1, 1, 0, 0, 0));
    }
}
