package com.myxcomp.ice.xtree.common;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class TimeMapper {

    public LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) return null;
        return instant.atOffset(ZoneOffset.UTC).toLocalDateTime();
    }

    public Instant toInstant(LocalDateTime localDateTime) {
        if (localDateTime == null) return null;
        return localDateTime.toInstant(ZoneOffset.UTC);
    }

    /**
     * Returns the current UTC instant. Sole entry-point for "now" across the application —
     * services and bootstrap collaborators MUST NOT call {@code Instant.now()} directly so
     * that time can be controlled in tests via Mockito.
     */
    public Instant now() {
        return Instant.now();
    }
}
