// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

import java.io.PrintStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON-lines logger on stderr: ts, level, msg, then caller fields. */
public final class Log {
    public enum Level {
        DEBUG, INFO, WARN, ERROR;

        public static Level parse(String s) {
            return switch (s.toLowerCase(java.util.Locale.ROOT)) {
                case "debug" -> DEBUG;
                case "info" -> INFO;
                case "warn" -> WARN;
                case "error" -> ERROR;
                default -> throw new IllegalArgumentException("unknown log level: " + s);
            };
        }
    }

    private final Level min;
    private final PrintStream out;

    public Log(Level min, PrintStream out) {
        this.min = min;
        this.out = out;
    }

    public boolean enabled(Level l) {
        return l.ordinal() >= min.ordinal();
    }

    /** Fields come as alternating key, value arguments. */
    public void log(Level l, String msg, Object... kv) {
        if (!enabled(l)) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", Instant.now().toString());
        m.put("level", l.name().toLowerCase(java.util.Locale.ROOT));
        m.put("msg", msg);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put(String.valueOf(kv[i]), v instanceof Number || v instanceof Boolean || v == null ? v : String.valueOf(v));
        }
        String line = Json.write(m);
        synchronized (out) {
            out.println(line);
            out.flush();
        }
    }

    public void debug(String msg, Object... kv) { log(Level.DEBUG, msg, kv); }
    public void info(String msg, Object... kv) { log(Level.INFO, msg, kv); }
    public void warn(String msg, Object... kv) { log(Level.WARN, msg, kv); }
    public void error(String msg, Object... kv) { log(Level.ERROR, msg, kv); }
}
