// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One client connection: reads request lines, answers them, and writes events
 * and responses from a bounded queue. Tick events are conflatable by
 * {@code (event, req_id, tick_type)}; every other message is delivered in
 * order or the connection ends ({@code client_too_slow}).
 */
final class Session implements Sink {
    static final String PROTOCOL_PREFIX = "perkbridge.ibshim/v";
    static final String PROTOCOL_MAJOR = "1";
    private static final Set<String> CONFLATABLE =
            Set.of("tick_price", "tick_size", "tick_string", "tick_generic");

    private static final class Out {
        byte[] bytes;
        final String key;

        Out(byte[] bytes, String key) {
            this.bytes = bytes;
            this.key = key;
        }
    }

    private final SocketChannel ch;
    private final Venue venue;
    private final AtomicLong seq;
    private final Log log;
    private final ServerConfig cfg;

    private final Object lock = new Object();
    private final Deque<Out> queue = new ArrayDeque<>();
    private final Map<String, Out> conflate = new HashMap<>();
    private long queuedBytes;
    private boolean closed;
    private boolean closeAfterFlush;
    private String closeReason = "";
    private volatile boolean done;
    private boolean helloSeen;

    Session(SocketChannel ch, Venue venue, AtomicLong seq, Log log, ServerConfig cfg) {
        this.ch = ch;
        this.venue = venue;
        this.seq = seq;
        this.log = log;
        this.cfg = cfg;
    }

    boolean finished() {
        return done;
    }

    /** Runs the session to its end on the calling thread. */
    void run() {
        Thread writer = new Thread(this::writeLoop, "ibshim-writer");
        writer.setDaemon(true);
        venue.attach(this);
        writer.start();
        try {
            readLoop();
        } catch (IOException e) {
            close("read_failed: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.error("session_failed", "error", e.toString());
            close("internal_error");
        } finally {
            boolean flushPending;
            synchronized (lock) {
                flushPending = closeAfterFlush;
            }
            try {
                if (flushPending) {
                    writer.join(2000); // let the last error line out
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            close("client_closed");
            try {
                writer.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            try {
                venue.detach();
            } catch (RuntimeException e) {
                log.error("detach_failed", "error", e.toString());
            }
            String reason;
            synchronized (lock) {
                reason = closeReason;
            }
            log.info("client_disconnected", "reason", reason);
            done = true;
        }
    }

    // ---------------------------------------------------------------- reading

    private void readLoop() throws IOException {
        InputStream in = Channels.newInputStream(ch);
        byte[] buf = new byte[8192];
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        while (true) {
            int n = in.read(buf);
            if (n < 0) {
                return;
            }
            for (int i = 0; i < n; i++) {
                byte b = buf[i];
                if (b == '\n') {
                    if (!handleLine(line.toByteArray())) {
                        return;
                    }
                    line.reset();
                } else {
                    line.write(b);
                    if (line.size() > cfg.maxLineBytes) {
                        sendError(null, OpException.BAD_REQUEST, "line longer than " + cfg.maxLineBytes + " bytes");
                        closeAfterFlush("line_too_long");
                        return;
                    }
                }
            }
        }
    }

    /** @return false when the session must end */
    private boolean handleLine(byte[] raw) {
        int len = raw.length;
        if (len > 0 && raw[len - 1] == '\r') {
            len--;
        }
        if (len == 0) {
            return true;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, 0, len))
                    .toString();
        } catch (CharacterCodingException e) {
            sendError(null, OpException.BAD_REQUEST, "line is not valid UTF-8");
            closeAfterFlush("bad_utf8");
            return false;
        }
        Map<String, Object> req;
        try {
            req = Json.parseObject(text);
        } catch (Json.ParseException e) {
            sendError(null, OpException.BAD_REQUEST, "invalid JSON: " + e.getMessage());
            return true;
        }
        Object id = req.get("id");
        if (id != null && !(id instanceof String) && !(id instanceof Long)) {
            sendError(null, OpException.BAD_REQUEST, "id must be a string");
            return true;
        }
        Object opv = req.get("op");
        if (!(opv instanceof String op) || op.isEmpty()) {
            sendError(id, OpException.BAD_REQUEST, "op is required");
            return true;
        }
        Object argsv = req.get("args");
        Map<String, Object> args;
        if (argsv == null) {
            args = new LinkedHashMap<>();
        } else if (argsv instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) argsv;
            args = m;
        } else {
            sendError(id, OpException.BAD_REQUEST, "args must be an object");
            return true;
        }
        if (log.enabled(Log.Level.DEBUG)) {
            log.debug("request", "op", op, "id", String.valueOf(id));
        }
        if (!helloSeen && !op.equals("hello")) {
            sendError(id, OpException.BAD_REQUEST, "the first request must be hello");
            return true;
        }
        try {
            Map<String, Object> result;
            switch (op) {
                case "hello" -> {
                    Object p = args.get("protocol");
                    if (!(p instanceof String ps) || !majorMatches(ps)) {
                        sendError(id, OpException.UNSUPPORTED_OP,
                                "protocol " + p + " not supported; this shim speaks " + PROTOCOL_PREFIX + PROTOCOL_MAJOR);
                        closeAfterFlush("protocol_mismatch");
                        return false;
                    }
                    helloSeen = true;
                    result = hello(args);
                }
                case "ping" -> result = new LinkedHashMap<>();
                default -> {
                    if (!venue.ops().contains(op)) {
                        throw new OpException(OpException.UNSUPPORTED_OP, "unknown op " + op);
                    }
                    result = venue.handle(op, args);
                }
            }
            sendResult(id, result);
        } catch (OpException e) {
            sendError(id, e.code(), e.getMessage(), e.venueCode());
        } catch (RuntimeException e) {
            log.error("op_failed", "op", op, "error", e.toString());
            sendError(id, OpException.INTERNAL, "internal error");
        }
        return true;
    }

    static boolean majorMatches(String protocol) {
        if (!protocol.startsWith(PROTOCOL_PREFIX)) {
            return false;
        }
        String rest = protocol.substring(PROTOCOL_PREFIX.length());
        int dot = rest.indexOf('.');
        String major = dot < 0 ? rest : rest.substring(0, dot);
        return major.equals(PROTOCOL_MAJOR);
    }

    private Map<String, Object> hello(Map<String, Object> args) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocol", PROTOCOL_PREFIX + PROTOCOL_MAJOR);
        r.put("shim", cfg.shimVersion);
        r.put("api_client", venue.apiClientVersion());
        List<Object> ops = new ArrayList<>();
        ops.add("hello");
        ops.add("ping");
        ops.addAll(venue.ops());
        r.put("ops", ops);
        log.info("client_hello", "client", String.valueOf(args.get("client")));
        return r;
    }

    // ---------------------------------------------------------------- writing

    private void sendResult(Object id, Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("ok", Boolean.TRUE);
        m.put("result", result == null ? new LinkedHashMap<String, Object>() : result);
        enqueue(Json.write(m), null);
    }

    private void sendError(Object id, String code, String message) {
        sendError(id, code, message, 0);
    }

    private void sendError(Object id, String code, String message, int venueCode) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) {
            m.put("id", id);
        }
        m.put("ok", Boolean.FALSE);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        if (message != null && !message.isEmpty()) {
            e.put("message", message);
        }
        if (venueCode != 0) {
            e.put("venue_code", (long) venueCode);
        }
        m.put("error", e);
        enqueue(Json.write(m), null);
    }

    @Override
    public long event(String name, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        long s;
        String key = null;
        synchronized (lock) {
            if (closed) {
                return 0;
            }
            s = seq.incrementAndGet();
            if (CONFLATABLE.contains(name)) {
                key = name + "/" + data.get("req_id") + "/" + data.get("tick_type");
            }
        }
        m.put("event", name);
        m.put("seq", s);
        m.put("at", Instant.now().toString());
        m.put("data", data);
        enqueue(Json.write(m), key);
        return s;
    }

    /** Heartbeat: sent every interval while connected. */
    void heartbeat() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("connected", venue.connected());
        event("heartbeat", d);
    }

    private void enqueue(String line, String key) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        boolean tooSlow = false;
        synchronized (lock) {
            if (closed) {
                return;
            }
            Out existing = key == null ? null : conflate.get(key);
            if (existing != null) {
                queuedBytes += bytes.length - existing.bytes.length;
                existing.bytes = bytes;
            } else {
                Out o = new Out(bytes, key);
                queue.addLast(o);
                if (key != null) {
                    conflate.put(key, o);
                }
                queuedBytes += bytes.length;
            }
            if (queuedBytes > cfg.maxQueueBytes) {
                tooSlow = true;
            }
            lock.notifyAll();
        }
        if (tooSlow) {
            log.warn("client_too_slow", "queued_bytes", queuedBytes);
            close("client_too_slow");
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                Out o;
                synchronized (lock) {
                    while (queue.isEmpty() && !closed) {
                        if (closeAfterFlush) {
                            closeLocked("closed_after_flush");
                            return;
                        }
                        lock.wait();
                    }
                    if (closed) {
                        return;
                    }
                    o = queue.pollFirst();
                    if (o.key != null) {
                        conflate.remove(o.key);
                    }
                    queuedBytes -= o.bytes.length;
                }
                ByteBuffer bb = ByteBuffer.wrap(o.bytes);
                while (bb.hasRemaining()) {
                    ch.write(bb);
                }
            }
        } catch (IOException e) {
            close("write_failed: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- closing

    /** Closes once everything already queued has been written. */
    private void closeAfterFlush(String reason) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closeReason = reason;
            closeAfterFlush = true;
            lock.notifyAll();
        }
    }

    /** Closes now and discards unsent events. */
    void close(String reason) {
        synchronized (lock) {
            closeLocked(reason);
        }
    }

    private void closeLocked(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        if (closeReason.isEmpty()) {
            closeReason = reason;
        }
        queue.clear();
        conflate.clear();
        queuedBytes = 0;
        lock.notifyAll();
        try {
            ch.close();
        } catch (IOException ignored) {
            // already closing
        }
    }
}
