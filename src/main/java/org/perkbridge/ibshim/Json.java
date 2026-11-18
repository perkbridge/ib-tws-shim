// SPDX-License-Identifier: GPL-3.0-only
package org.perkbridge.ibshim;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON codec (RFC 8259) for the shim protocol. Values are
 * {@code Map<String,Object>} (insertion ordered), {@code List<Object>},
 * {@code String}, {@code Long}, {@code BigDecimal}, {@code Boolean} and
 * {@code null}. Integers that fit a long parse as {@code Long}, every other
 * number as {@code BigDecimal}. No dependency is needed for this, which keeps
 * the shim jar free of third-party code.
 */
public final class Json {
    private Json() {}

    /** Thrown for malformed JSON. */
    public static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        public ParseException(String message) {
            super(message);
        }
    }

    private static final int MAX_DEPTH = 64;

    public static Object parse(String text) throws ParseException {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw new ParseException("trailing characters at " + p.pos);
        }
        return v;
    }

    /** Parses a JSON object; anything else is a {@link ParseException}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws ParseException {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new ParseException("not a JSON object");
        }
        return (Map<String, Object>) v;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        write(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer || v instanceof Short) {
            sb.append(v);
        } else if (v instanceof BigDecimal bd) {
            sb.append(bd.toPlainString());
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(new BigDecimal(Double.toString(d)).toPlainString());
            }
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON value type " + v.getClass().getName());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) throws ParseException {
            if (depth > MAX_DEPTH) {
                throw new ParseException("nesting too deep");
            }
            if (pos >= s.length()) {
                throw new ParseException("unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw new ParseException("unexpected character at " + pos);
            }
        }

        Object literal(String word, Object val) throws ParseException {
            if (!s.startsWith(word, pos)) {
                throw new ParseException("bad literal at " + pos);
            }
            pos += word.length();
            return val;
        }

        Map<String, Object> object(int depth) throws ParseException {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new ParseException("object key expected at " + pos);
                }
                String k = string();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new ParseException("':' expected at " + pos);
                }
                pos++;
                skipWs();
                if (m.containsKey(k)) {
                    throw new ParseException("duplicate key " + k);
                }
                m.put(k, value(depth + 1));
                skipWs();
                if (pos >= s.length()) {
                    throw new ParseException("unexpected end");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new ParseException("',' or '}' expected at " + (pos - 1));
                }
            }
        }

        List<Object> array(int depth) throws ParseException {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth + 1));
                skipWs();
                if (pos >= s.length()) {
                    throw new ParseException("unexpected end");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new ParseException("',' or ']' expected at " + (pos - 1));
                }
            }
        }

        String string() throws ParseException {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new ParseException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw new ParseException("control character in string at " + (pos - 1));
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw new ParseException("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw new ParseException("short \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new ParseException("bad \\u escape at " + pos);
                        }
                        pos += 4;
                    }
                    default -> throw new ParseException("bad escape at " + (pos - 1));
                }
            }
        }

        Object number() throws ParseException {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            int digits = 0;
            while (pos < s.length() && Character.isDigit(s.charAt(pos)) && s.charAt(pos) < 128) {
                pos++;
                digits++;
            }
            if (digits == 0) {
                throw new ParseException("digits expected at " + pos);
            }
            boolean integral = true;
            if (pos < s.length() && s.charAt(pos) == '.') {
                integral = false;
                pos++;
                int frac = 0;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                    frac++;
                }
                if (frac == 0) {
                    throw new ParseException("fraction digits expected at " + pos);
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                integral = false;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int exp = 0;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                    exp++;
                }
                if (exp == 0) {
                    throw new ParseException("exponent digits expected at " + pos);
                }
            }
            String t = s.substring(start, pos);
            if (integral) {
                try {
                    return Long.valueOf(t);
                } catch (NumberFormatException ex) {
                    // fall through to BigDecimal
                }
            }
            try {
                return new BigDecimal(t);
            } catch (NumberFormatException ex) {
                throw new ParseException("bad number " + t);
            }
        }
    }
}
