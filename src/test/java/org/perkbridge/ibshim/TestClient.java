// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Line-oriented test client; a reader thread feeds a queue so reads can time out. */
final class TestClient implements AutoCloseable {
    final SocketChannel ch;
    final BlockingQueue<String> lines = new ArrayBlockingQueue<>(1_000_000);
    private static final String EOF = "\u0000EOF";
    private int nextId = 1;
    /** While true the reader stops reading the socket, like a stalled consumer. */
    volatile boolean paused;

    TestClient(Path socket) throws IOException {
        ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(socket));
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8))) {
                String l;
                while (true) {
                    while (paused) {
                        Thread.sleep(5);
                    }
                    l = r.readLine();
                    if (l == null) {
                        break;
                    }
                    lines.put(l);
                }
            } catch (IOException | InterruptedException ignored) {
                // closed
            } finally {
                lines.offer(EOF);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    void sendRaw(String s) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
        while (b.hasRemaining()) {
            ch.write(b);
        }
    }

    String send(String op, String argsJson) throws IOException {
        String id = String.valueOf(nextId++);
        sendRaw("{\"id\":\"" + id + "\",\"op\":\"" + op + "\"" + (argsJson == null ? "" : ",\"args\":" + argsJson) + "}\n");
        return id;
    }

    /** Next line, or null on timeout; "EOF" marker when the server closed. */
    String next(long ms) throws InterruptedException {
        if (!pending.isEmpty()) {
            return pending.remove(0);
        }
        return lines.poll(ms, TimeUnit.MILLISECONDS);
    }

    /** Lines that arrived before a response we were waiting for (events). */
    private final List<String> pending = new ArrayList<>();

    static boolean isEof(String l) {
        return EOF.equals(l);
    }

    Map<String, Object> nextObj(long ms) throws Exception {
        String l = next(ms);
        if (l == null) {
            throw new AssertionError("timeout waiting for a line");
        }
        if (isEof(l)) {
            throw new AssertionError("connection closed");
        }
        return Json.parseObject(l);
    }

    /** Skips events until the response with this id. */
    Map<String, Object> response(String id, long ms) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        while (true) {
            String l = lines.poll(Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())), TimeUnit.MILLISECONDS);
            if (l == null) {
                throw new AssertionError("timeout waiting for response " + id);
            }
            if (isEof(l)) {
                throw new AssertionError("connection closed");
            }
            Map<String, Object> m = Json.parseObject(l);
            if (id.equals(m.get("id"))) {
                return m;
            }
            pending.add(l);
        }
    }

    Map<String, Object> call(String op, String argsJson) throws Exception {
        return response(send(op, argsJson), 5000);
    }

    /** Collects events with this name until none arrives for {@code quietMs}. */
    List<Map<String, Object>> events(String name, long quietMs) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        while (true) {
            String l = next(quietMs);
            if (l == null || isEof(l)) {
                return out;
            }
            Map<String, Object> m = Json.parseObject(l);
            if (name.equals(m.get("event"))) {
                out.add(m);
            }
        }
    }

    Map<String, Object> waitEvent(String name, long ms) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        while (true) {
            long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            if (left <= 0) {
                throw new AssertionError("no " + name + " event within " + ms + " ms");
            }
            Map<String, Object> m = nextObj(left);
            if (name.equals(m.get("event"))) {
                return m;
            }
        }
    }

    @Override
    public void close() {
        try {
            ch.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
