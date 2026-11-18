// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim.ib;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Conversions between IB's time strings and RFC 3339 UTC. */
final class Times {
    private Times() {}

    private static final DateTimeFormatter IB_OUT =
            DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter IB_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss");

    /** RFC 3339 to IB's {@code yyyyMMdd HH:mm:ss UTC}. */
    static String toIb(String rfc3339) {
        try {
            return IB_OUT.format(Instant.parse(rfc3339));
        } catch (DateTimeParseException e) {
            try {
                return IB_OUT.format(java.time.OffsetDateTime.parse(rfc3339).toInstant());
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("not an RFC 3339 time: " + rfc3339);
            }
        }
    }

    static String epochToRfc3339(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).toString();
    }

    /**
     * Parses what IB sends: epoch seconds, {@code yyyyMMdd HH:mm:ss zone} or
     * {@code yyyyMMdd  HH:mm:ss zone} (one or two spaces). Returns null when
     * the text carries no zone or cannot be parsed, so that the caller passes
     * the raw string instead of guessing a zone.
     */
    static String toRfc3339(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.matches("\\d{9,11}")) {
            return epochToRfc3339(Long.parseLong(t));
        }
        String[] p = t.split("\\s+");
        if (p.length != 3) {
            return null;
        }
        try {
            LocalDateTime ldt = LocalDateTime.parse(p[0] + " " + p[1], IB_DATE_TIME);
            // region ids ("US/Eastern", "Europe/London"), UTC and GMT only: three-letter
            // abbreviations such as BST, IST or CST name several zones, so the caller keeps the raw text
            if (!(p[2].contains("/") || p[2].equals("UTC") || p[2].equals("GMT"))) {
                return null;
            }
            ZoneId z = ZoneId.of(p[2]);
            return ZonedDateTime.of(ldt, z).toInstant().toString();
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }
}
