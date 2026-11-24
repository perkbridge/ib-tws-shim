// SPDX-License-Identifier: GPL-3.0-only
package org.perkbridge.ibshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerTest {
    @TempDir Path dir;
    Path sock;
    FakeVenue venue;
    Server server;
    Thread serveThread;
    ServerConfig cfg;

    void start(java.util.function.Consumer<ServerConfig> tweak) throws Exception {
        sock = dir.resolve("s.sock");
        venue = new FakeVenue();
        cfg = new ServerConfig(sock);
        cfg.heartbeatMillis = 60_000;
        tweak.accept(cfg);
        server = new Server(cfg, venue, new Log(Log.Level.WARN, new PrintStream(new ByteArrayOutputStream())));
        server.bind();
        serveThread = new Thread(server::serve, "serve");
        serveThread.setDaemon(true);
        serveThread.start();
    }

    @BeforeEach
    void defaultStart() throws Exception {
        start(c -> {});
    }

    @AfterEach
    void stop() {
        server.close();
    }

    TestClient helloClient() throws Exception {
        TestClient c = new TestClient(sock);
        Map<String, Object> r = c.call("hello", "{\"protocol\":\"perkbridge.ibshim/v1\",\"client\":\"test/1\"}");
        assertEquals(Boolean.TRUE, r.get("ok"));
        return c;
    }

    @SuppressWarnings("unchecked")
    @Test
    void helloAnswersWithProtocolShimApiAndOps() throws Exception {
        try (TestClient c = new TestClient(sock)) {
            Map<String, Object> r = c.call("hello", "{\"protocol\":\"perkbridge.ibshim/v1\",\"client\":\"test/1\"}");
            Map<String, Object> res = (Map<String, Object>) r.get("result");
            assertEquals("perkbridge.ibshim/v1", res.get("protocol"));
            assertEquals("ib-tws-shim/0.3.0", res.get("shim"));
            assertEquals("fake-api/1", res.get("api_client"));
            assertEquals(List.of("hello", "ping", "echo", "fail"), res.get("ops"));
        }
    }

    @Test
    void socketIsGroupReadWriteOnly() throws Exception {
        assertEquals("rw-rw----", PosixFilePermissions.toString(Files.getPosixFilePermissions(sock)));
    }

    @Test
    void firstRequestMustBeHello() throws Exception {
        try (TestClient c = new TestClient(sock)) {
            Map<String, Object> r = c.call("ping", null);
            assertEquals(Boolean.FALSE, r.get("ok"));
            assertEquals("bad_request", ((Map<?, ?>) r.get("error")).get("code"));
            // the connection stays usable: hello still works
            assertEquals(Boolean.TRUE, c.call("hello", "{\"protocol\":\"perkbridge.ibshim/v1\"}").get("ok"));
        }
    }

    @Test
    void protocolMajorMismatchIsRefusedAndClosed() throws Exception {
        try (TestClient c = new TestClient(sock)) {
            Map<String, Object> r = c.call("hello", "{\"protocol\":\"perkbridge.ibshim/v2\"}");
            assertEquals("unsupported_op", ((Map<?, ?>) r.get("error")).get("code"));
            assertTrue(TestClient.isEof(c.next(3000)));
        }
        assertTrue(Session.majorMatches("perkbridge.ibshim/v1.4"));
        assertFalse(Session.majorMatches("perkbridge.ibshim/v10"));
        assertFalse(Session.majorMatches("other/v1"));
    }

    @Test
    void pingUnknownOpAndVenueErrors() throws Exception {
        try (TestClient c = helloClient()) {
            assertEquals(Boolean.TRUE, c.call("ping", null).get("ok"));
            Map<String, Object> u = c.call("nope", "{}");
            assertEquals("unsupported_op", ((Map<?, ?>) u.get("error")).get("code"));
            Map<String, Object> f = c.call("fail", "{}");
            Map<?, ?> e = (Map<?, ?>) f.get("error");
            assertEquals("venue_error", e.get("code"));
            assertEquals(201L, e.get("venue_code"));
            assertEquals("boom", e.get("message"));
            Map<String, Object> echo = c.call("echo", "{\"x\":\"1\"}");
            assertEquals(Map.of("x", "1"), echo.get("result"));
        }
    }

    @Test
    void secondClientGetsBusyAndIsClosedWithoutId() throws Exception {
        try (TestClient first = helloClient(); TestClient second = new TestClient(sock)) {
            assertEquals("{\"ok\":false,\"error\":{\"code\":\"busy\"}}", second.next(3000));
            assertTrue(TestClient.isEof(second.next(3000)));
            assertEquals(Boolean.TRUE, first.call("ping", null).get("ok"));
        }
    }

    @Test
    void framingErrorsAreAnsweredWithoutKillingTheConnection() throws Exception {
        try (TestClient c = helloClient()) {
            c.sendRaw("not json\n");
            Map<String, Object> m = c.nextObj(3000);
            assertEquals("bad_request", ((Map<?, ?>) m.get("error")).get("code"));
            assertFalse(m.containsKey("id"));
            c.sendRaw("[1]\n");
            assertEquals("bad_request", ((Map<?, ?>) c.nextObj(3000).get("error")).get("code"));
            c.sendRaw("{\"id\":\"9\"}\n");
            Map<String, Object> noOp = c.nextObj(3000);
            assertEquals("9", noOp.get("id"));
            c.sendRaw("{\"id\":\"8\",\"op\":\"ping\",\"args\":[]}\n");
            assertEquals("bad_request", ((Map<?, ?>) c.nextObj(3000).get("error")).get("code"));
            c.sendRaw("\n\r\n"); // blank lines are ignored
            c.sendRaw("{\"id\":\"7\",\"op\":\"ping\"}\r\n"); // CRLF tolerated
            assertEquals("7", c.nextObj(3000).get("id"));
        }
    }

    @Test
    void invalidUtf8EndsTheConnection() throws Exception {
        try (TestClient c = helloClient()) {
            c.ch.write(java.nio.ByteBuffer.wrap(new byte[] {'{', (byte) 0xff, (byte) 0xfe, '}', '\n'}));
            assertEquals("bad_request", ((Map<?, ?>) c.nextObj(3000).get("error")).get("code"));
            assertTrue(TestClient.isEof(c.next(3000)));
        }
    }

    @Test
    void lineLongerThanOneMiBIsRefusedAndClosed() throws Exception {
        try (TestClient c = helloClient()) {
            c.sendRaw("{\"id\":\"1\",\"op\":\"ping\",\"pad\":\"" + "x".repeat((1 << 20) + 10) + "\"}\n");
            Map<String, Object> m = c.nextObj(5000);
            assertEquals("bad_request", ((Map<?, ?>) m.get("error")).get("code"));
            assertTrue(TestClient.isEof(c.next(3000)));
        }
    }

    @Test
    void lineExactlyAtTheLimitIsAccepted() throws Exception {
        try (TestClient c = helloClient()) {
            String head = "{\"id\":\"1\",\"op\":\"ping\",\"pad\":\"";
            String tail = "\"}";
            int pad = (1 << 20) - head.length() - tail.length();
            c.sendRaw(head + "x".repeat(pad) + tail + "\n");
            assertEquals(Boolean.TRUE, c.nextObj(5000).get("ok"));
        }
    }

    @Test
    void eventsCarryIncreasingSeqAndSeqSurvivesClients() throws Exception {
        long last;
        try (TestClient c = helloClient()) {
            Sink s = venue.sink;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("a", 1L);
            long s1 = s.event("x", d);
            long s2 = s.event("x", d);
            assertEquals(s1 + 1, s2);
            Map<String, Object> e1 = c.waitEvent("x", 3000);
            assertEquals(s1, e1.get("seq"));
            assertNotNull(e1.get("at"));
            assertEquals(Map.of("a", 1L), e1.get("data"));
            last = s2;
        }
        assertTrue(venue.detachLatch.await(3, TimeUnit.SECONDS));
        // a new client (after the busy window closes) sees a higher seq: no reuse in one process
        TestClient c2 = null;
        for (int i = 0; i < 50 && c2 == null; i++) {
            TestClient t = new TestClient(sock);
            String l = t.next(300);
            if (l != null && l.contains("busy")) {
                t.close();
                Thread.sleep(50);
                continue;
            }
            c2 = t;
        }
        assertNotNull(c2);
        final TestClient c3 = c2;
        try (c3) {
            c3.call("hello", "{\"protocol\":\"perkbridge.ibshim/v1\"}");
            assertTrue(venue.sink.event("y", new LinkedHashMap<>()) > last);
        }
    }

    @Test
    void clientLossDetachesTheVenueAndTheNextClientConnects() throws Exception {
        TestClient c = helloClient();
        assertEquals(1, venue.attached.get());
        c.close();
        assertTrue(venue.detachLatch.await(3, TimeUnit.SECONDS));
        assertEquals(1, venue.detached.get());
        assertNull(venue.sink);
        // listening again
        TestClient next = null;
        for (int i = 0; i < 50 && next == null; i++) {
            TestClient t = new TestClient(sock);
            try {
                t.send("hello", "{\"protocol\":\"perkbridge.ibshim/v1\"}");
            } catch (java.io.IOException busyAndClosed) {
                t.close();
                Thread.sleep(50);
                continue;
            }
            String l = t.next(500);
            if (l != null && l.contains("\"ok\":true")) {
                next = t;
            } else {
                t.close();
                Thread.sleep(50);
            }
        }
        assertNotNull(next);
        next.close();
    }

    @Test
    void heartbeatArrivesAboutEveryInterval() throws Exception {
        server.close();
        start(c -> c.heartbeatMillis = 100);
        venue.connected = true;
        try (TestClient c = helloClient()) {
            Map<String, Object> h1 = c.waitEvent("heartbeat", 3000);
            Map<String, Object> h2 = c.waitEvent("heartbeat", 3000);
            assertEquals(Map.of("connected", true), h1.get("data"));
            assertTrue(((Long) h2.get("seq")) > (Long) h1.get("seq"));
        }
    }

    @Test
    void ticksAreConflatedUnderLoadAndNeverEndTheConnection() throws Exception {
        try (TestClient c = helloClient()) {
            Sink s = venue.sink;
            c.paused = true; // a consumer that has stopped reading
            int n = 300_000;
            for (int i = 1; i <= n; i++) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("req_id", 1L);
                d.put("tick_type", 4L);
                d.put("price", String.valueOf(i));
                d.put("pad", "x".repeat(200));
                s.event("tick_price", d);
            }
            // a different key and a non-tick event must both survive
            Map<String, Object> d2 = new LinkedHashMap<>();
            d2.put("req_id", 2L);
            d2.put("tick_type", 4L);
            d2.put("price", "7");
            s.event("tick_price", d2);
            s.event("order_status", new LinkedHashMap<>());
            c.paused = false;
            List<String> got = new ArrayList<>();
            boolean sawStatus = false;
            while (!sawStatus) {
                String l = c.next(5000);
                assertNotNull(l, "timed out");
                got.add(l);
                sawStatus = l.contains("order_status");
            }
            assertTrue(got.size() < n / 10, "conflation must drop most ticks, got " + got.size());
            String lastReq1 = null;
            for (String l : got) {
                if (l.contains("\"req_id\":1,")) {
                    lastReq1 = l;
                }
            }
            assertNotNull(lastReq1);
            assertTrue(lastReq1.contains("\"price\":\"" + n + "\""), "latest tick must win: " + lastReq1);
            assertTrue(got.stream().anyMatch(l -> l.contains("\"req_id\":2,")));
            assertEquals(Boolean.TRUE, c.call("ping", null).get("ok"));
        }
    }

    @Test
    void slowClientOverflowEndsTheConnectionWithoutDroppingSilently() throws Exception {
        server.close();
        start(c -> c.maxQueueBytes = 256 * 1024);
        try (TestClient c = helloClient()) {
            Sink s = venue.sink;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("blob", "x".repeat(1000));
            // never read: socket buffers fill, then the queue passes the cap
            for (int i = 0; i < 100_000 && venue.sink != null; i++) {
                if (s.event("order_status", d) == 0) {
                    break;
                }
            }
            assertTrue(venue.detachLatch.await(5, TimeUnit.SECONDS), "venue must be detached after client_too_slow");
        }
    }

    @Test
    void stopFreesTheSocketAndAStaleSocketFileIsReplaced() throws Exception {
        server.close();
        assertFalse(Files.exists(sock));
        start(c -> {});
        server.close();
        // leave a dead socket file behind, as a crashed process would
        Path p = dir.resolve("dead.sock");
        try (var ch = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            ch.bind(java.net.UnixDomainSocketAddress.of(p));
        }
        assertTrue(Files.exists(p));
        Server s2 = new Server(new ServerConfig(p), new FakeVenue(), new Log(Log.Level.ERROR, new PrintStream(new ByteArrayOutputStream())));
        s2.bind();
        s2.close();
        start(c -> {}); // for @AfterEach
    }

    @Test
    void bindRefusesLiveRegularAndMissingDirectoryPaths() throws Exception {
        Log quiet = new Log(Log.Level.ERROR, new PrintStream(new ByteArrayOutputStream()));
        // a live listener
        assertThrows(Server.SocketUnusableException.class, () -> new Server(new ServerConfig(sock), new FakeVenue(), quiet).bind());
        // a regular file in the way
        Path f = dir.resolve("file");
        Files.writeString(f, "x");
        assertThrows(Server.SocketUnusableException.class, () -> new Server(new ServerConfig(f), new FakeVenue(), quiet).bind());
        // missing directory
        assertThrows(Server.SocketUnusableException.class,
                () -> new Server(new ServerConfig(dir.resolve("nodir/s.sock")), new FakeVenue(), quiet).bind());
    }

    @Test
    void seqNeverGoesBackwardsOnTheWireWithInterleavedConflatedTicksAndConcurrentEmitters() throws Exception {
        try (TestClient c = helloClient()) {
            Sink s = venue.sink;
            c.paused = true;
            Thread[] ts = new Thread[4];
            for (int t = 0; t < ts.length; t++) {
                final int id = t;
                ts[t] = new Thread(() -> {
                    for (int i = 0; i < 20_000; i++) {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("req_id", (long) (i % 3)); // few keys: heavy interleaving
                        d.put("tick_type", 4L);
                        d.put("price", String.valueOf(i));
                        d.put("pad", "x".repeat(300));
                        s.event(i % 50 == 0 ? "order_status" : "tick_price", d);
                    }
                });
                ts[t].start();
            }
            for (Thread t : ts) {
                t.join();
            }
            s.event("heartbeat", new LinkedHashMap<>());
            c.paused = false;
            long last = 0;
            int n = 0;
            while (true) {
                String l = c.next(5000);
                assertNotNull(l);
                Map<String, Object> m = Json.parseObject(l);
                if (!m.containsKey("seq")) {
                    continue;
                }
                long seq = (Long) m.get("seq");
                assertTrue(seq > last, "seq went backwards: " + last + " then " + seq);
                last = seq;
                n++;
                if ("heartbeat".equals(m.get("event"))) {
                    break;
                }
            }
            assertTrue(n > 1000);
        }
    }

    @Test
    void aConnectionThatNeverSaysHelloIsClosed() throws Exception {
        server.close();
        start(c -> {
            c.heartbeatMillis = 100;
            c.helloTimeoutMillis = 300;
        });
        try (TestClient c = new TestClient(sock)) {
            String l;
            do {
                l = c.next(3000);
                assertNotNull(l, "must be closed after the hello timeout");
            } while (!TestClient.isEof(l));
        }
    }

    @Test
    void bindLeavesNoTemporaryFilesBehind() throws Exception {
        try (var names = Files.list(dir)) {
            assertEquals(List.of("s.sock"), names.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void numericIdsAreRefusedBecauseTheSpecSaysIdIsAString() throws Exception {
        try (TestClient c = helloClient()) {
            c.sendRaw("{\"id\":5,\"op\":\"ping\"}\n");
            assertEquals("bad_request", ((Map<?, ?>) c.nextObj(3000).get("error")).get("code"));
        }
    }

    @Test
    void replacedTicksAreFreedSoTheQueueCapStillHolds() throws Exception {
        server.close();
        start(c -> c.maxQueueBytes = 1 << 20);
        try (TestClient c = helloClient()) {
            Sink s = venue.sink;
            c.paused = true;
            // one key, endless replacements, a stalled reader: memory and counted bytes stay bounded
            for (int i = 0; i < 2_000_000; i++) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("req_id", 1L);
                d.put("tick_type", 4L);
                d.put("price", String.valueOf(i));
                if (s.event("tick_price", d) == 0) {
                    break;
                }
            }
            c.paused = false;
            assertEquals(Boolean.TRUE, c.call("ping", null).get("ok"), "a single conflated key must not trip the cap");
        }
    }
}
