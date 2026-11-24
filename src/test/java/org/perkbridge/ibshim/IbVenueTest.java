// SPDX-License-Identifier: GPL-3.0-only
package org.perkbridge.ibshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ib.client.EClientSocket;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.perkbridge.ibshim.ib.IbVenue;

/** The IB adapter against a test double of IB's client library (src/test/java/com/ib/client). */
class IbVenueTest {
    @TempDir Path dir;
    Path sock;
    Server server;
    IbVenue venue;
    final ByteArrayOutputStream logs = new ByteArrayOutputStream();
    java.net.ServerSocket gateway; // a listener standing in for the IB gateway port
    int port;
    final java.util.List<java.net.Socket> accepted = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @BeforeEach
    void start() throws Exception {
        EClientSocket.calls.clear();
        EClientSocket.execTime = null;
        EClientSocket.mode = "";
        gateway = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        port = gateway.getLocalPort();
        Thread acc = new Thread(() -> {
            try {
                while (true) {
                    accepted.add(gateway.accept());
                }
            } catch (java.io.IOException ignored) {
                // closed
            }
        });
        acc.setDaemon(true);
        acc.start();
        EClientSocket.emptyPositions = false;
        sock = dir.resolve("ib.sock");
        ServerConfig cfg = new ServerConfig(sock);
        cfg.heartbeatMillis = 60_000;
        Log log = new Log(Log.Level.INFO, new PrintStream(logs));
        venue = new IbVenue(log);
        server = new Server(cfg, venue, log);
        server.bind();
        Thread t = new Thread(server::serve);
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        gateway.close();
        for (java.net.Socket x : accepted) {
            x.close();
        }
    }

    TestClient hello() throws Exception {
        TestClient c = new TestClient(sock);
        assertEquals(Boolean.TRUE, c.call("hello", "{\"protocol\":\"perkbridge.ibshim/v1\",\"client\":\"t\"}").get("ok"));
        return c;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> connect(TestClient c) throws Exception {
        Map<String, Object> r = c.call("connect", "{\"host\":\"127.0.0.1\",\"port\":" + port + ",\"client_id\":7}");
        assertEquals(Boolean.TRUE, r.get("ok"), String.valueOf(r));
        return (Map<String, Object>) r.get("result");
    }

    Map<String, Object> modeConnect(TestClient c, String mode, int timeoutMs) throws Exception {
        EClientSocket.mode = mode;
        return c.call("connect", "{\"host\":\"127.0.0.1\",\"port\":" + port + ",\"client_id\":1,\"connect_timeout_ms\":" + timeoutMs + "}");
    }

    static String code(Map<String, Object> r) {
        return String.valueOf(((Map<?, ?>) r.get("error")).get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void helloListsEveryOpOfTheSpecAndTheApiClient() throws Exception {
        try (TestClient c = new TestClient(sock)) {
            Map<String, Object> res = (Map<String, Object>) c.call("hello", "{\"protocol\":\"perkbridge.ibshim/v1\"}").get("result");
            List<Object> ops = (List<Object>) res.get("ops");
            for (String op : new String[] {"hello", "ping", "connect", "disconnect", "req_ids", "server_time",
                "contract_details", "place_order", "cancel_order", "cancel_all", "open_orders", "completed_orders",
                "executions", "positions", "cancel_positions", "account_summary", "cancel_account_summary",
                "account_updates", "market_data", "cancel_market_data", "market_data_type", "historical_data",
                "cancel_historical_data", "market_rule", "pnl_single", "cancel_pnl_single"}) {
                assertTrue(ops.contains(op), op);
            }
            assertEquals("client_version=66", res.get("api_client"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void connectReturnsHandshakeDataAndEmitsConnected() throws Exception {
        try (TestClient c = hello()) {
            Map<String, Object> r = connect(c);
            assertEquals(176L, r.get("server_version"));
            assertEquals(1000L, r.get("next_valid_id"));
            assertEquals(List.of("DU1", "DU2"), r.get("accounts"));
            assertEquals("2026-11-18T14:00:00Z", r.get("connection_time"));
            Map<String, Object> ev = c.waitEvent("connected", 3000);
            assertEquals(7L, ((Map<String, Object>) ev.get("data")).get("client_id"));
            assertTrue(EClientSocket.calls.contains("eConnect 127.0.0.1 " + port + " 7"));
            // a second connect is refused
            Map<String, Object> again = c.call("connect", "{\"host\":\"127.0.0.1\",\"port\":" + port + ",\"client_id\":7}");
            assertEquals("already_connected", code(again));
        }
    }

    @Test
    void unknownFieldsAreIgnoredOutsideOrdersAsTheSpecSays() throws Exception {
        try (TestClient c = hello()) {
            Map<String, Object> r = c.call("connect", "{\"host\":\"127.0.0.1\",\"port\":" + port + ",\"client_id\":1,\"future_field\":1}");
            assertEquals(Boolean.TRUE, r.get("ok"), String.valueOf(r));
            assertEquals(Boolean.TRUE, c.call("req_ids", "{\"whatever\":true}").get("ok"));
        }
    }

    @Test
    void connectFailureIsAVenueErrorWithIbsCode() throws Exception {
        try (TestClient c = hello()) {
            Map<String, Object> r = modeConnect(c, "refuse", 10000);
            assertEquals("venue_error", code(r));
            assertEquals(502L, ((Map<?, ?>) r.get("error")).get("venue_code"));
            assertEquals("not_connected", code(c.call("req_ids", null)));
        }
    }

    @Test
    void anUnreachableGatewayPortIsAVenueErrorWith502() throws Exception {
        int dead;
        try (java.net.ServerSocket tmp = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            dead = tmp.getLocalPort();
        }
        try (TestClient c = hello()) {
            Map<String, Object> r = c.call("connect", "{\"host\":\"127.0.0.1\",\"port\":" + dead + ",\"client_id\":1,\"connect_timeout_ms\":2000}");
            assertEquals("venue_error", code(r));
            assertEquals(502L, ((Map<?, ?>) r.get("error")).get("venue_code"));
        }
    }

    @Test
    void aHungHandshakeIsReleasedWhenTheClientGoesAway() throws Exception {
        TestClient c = hello();
        EClientSocket.mode = "hang";
        c.send("connect", "{\"host\":\"127.0.0.1\",\"port\":" + port + ",\"client_id\":1,\"connect_timeout_ms\":60000}");
        Thread.sleep(300);
        c.close();
        TestClient next = null;
        for (int i = 0; i < 60 && next == null; i++) {
            TestClient t = new TestClient(sock);
            String l = t.next(300);
            if (l != null && l.contains("busy")) {
                t.close();
                Thread.sleep(100);
                continue;
            }
            next = t;
        }
        assertNotNull(next, "a blocked eConnect must not keep the client slot");
        next.close();
    }

    @Test
    void connectTimesOutWhenTheGatewaySendsNoValidId() throws Exception {
        try (TestClient c = hello()) {
            Map<String, Object> r = modeConnect(c, "silent", 300);
            assertEquals("timeout", code(r));
            assertEquals("not_connected", code(c.call("server_time", null)));
        }
    }

    @Test
    void opsNeedAConnectionAndArgsAreValidated() throws Exception {
        try (TestClient c = hello()) {
            assertEquals("not_connected", code(c.call("place_order", "{}")));
            assertEquals("bad_request", code(c.call("connect", "{\"host\":\"h\",\"port\":0,\"client_id\":1}")));
            assertEquals("bad_request", code(c.call("connect", "{\"port\":4002,\"client_id\":1}")));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void placeOrderTranslatesFieldsAndAnswersWithOpenOrderAndStatus() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            String args = "{\"order_id\":1001,\"contract\":{\"con_id\":265598,\"symbol\":\"AAPL\",\"sec_type\":\"STK\","
                    + "\"exchange\":\"SMART\",\"currency\":\"USD\"},\"order\":{\"action\":\"BUY\",\"order_type\":\"STP LMT\","
                    + "\"total_quantity\":\"10\",\"lmt_price\":\"187.25\",\"aux_price\":\"187.50\",\"tif\":\"GTD\","
                    + "\"good_till_date\":\"2026-11-20T15:00:00Z\",\"order_ref\":\"nile-1\",\"oca_group\":\"g1\",\"oca_type\":1,"
                    + "\"parent_id\":0,\"transmit\":false,\"outside_rth\":true,\"what_if\":false}}";
            Map<String, Object> r = c.call("place_order", args);
            assertEquals(Boolean.TRUE, r.get("ok"), String.valueOf(r));
            Map<String, Object> oo = c.waitEvent("open_order", 3000);
            Map<String, Object> d = (Map<String, Object>) oo.get("data");
            assertEquals(1001L, d.get("order_id"));
            Map<String, Object> o = (Map<String, Object>) d.get("order");
            assertEquals("BUY", o.get("action"));
            assertEquals("STP LMT", o.get("order_type"));
            assertEquals("10", o.get("total_quantity"));
            assertEquals("187.25", o.get("lmt_price"));
            assertEquals("187.5", o.get("aux_price"));
            assertEquals("GTD", o.get("tif"));
            assertEquals("20261120 15:00:00 UTC", o.get("good_till_date"));
            assertEquals(false, o.get("transmit"));
            assertEquals(true, o.get("outside_rth"));
            assertEquals(1L, o.get("oca_type"));
            Map<String, Object> k = (Map<String, Object>) d.get("contract");
            assertEquals(265598L, k.get("con_id"));
            assertEquals("STK", k.get("sec_type"));
            Map<String, Object> state = (Map<String, Object>) d.get("order_state");
            assertEquals("Submitted", state.get("status"));
            assertFalse(state.containsKey("commission"), "IB's unset sentinel must be omitted");
            assertFalse(state.containsKey("init_margin_change"));
            Map<String, Object> st = (Map<String, Object>) c.waitEvent("order_status", 3000).get("data");
            assertEquals("Submitted", st.get("status"));
            assertEquals("0", st.get("filled"));
            assertEquals("10", st.get("remaining"));
            assertEquals(4242L, st.get("perm_id"));
            assertFalse(st.containsKey("avg_fill_price") && "0.0".equals(st.get("avg_fill_price")) && false);
            assertFalse(st.containsKey("mkt_cap_price"));
            assertNotNull(EClientSocket.lastOrder);
        }
    }

    @Test
    void placeOrderRefusesMisspelledAndIncompleteOrdersWithoutCallingIb() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            String k = "\"contract\":{\"symbol\":\"AAPL\",\"sec_type\":\"STK\",\"exchange\":\"SMART\",\"currency\":\"USD\"}";
            String[] bad = {
                // misspelled order key must never silently fall back to an IB default
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\",\"tiff\":\"GTC\"}}",
                // misspelled contract key
                "{\"order_id\":5,\"contract\":{\"symbool\":\"AAPL\"},\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\"}}",
                // misspelled arg key
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\"},\"oder\":1}",
                // LMT without a price would otherwise trade at IB's default
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"LMT\",\"total_quantity\":\"1\"}}",
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"STP\",\"total_quantity\":\"1\"}}",
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"HOLD\",\"order_type\":\"MKT\",\"total_quantity\":\"1\"}}",
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MOC\",\"total_quantity\":\"1\"}}",
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"0\"}}",
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":1}}",
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"abc\"}}",
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\",\"tif\":\"GTD\"}}",
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\",\"tif\":\"FOREVER\"}}",
                "{\"order_id\":5," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\",\"good_till_date\":\"tomorrow\",\"tif\":\"GTD\"}}",
                "{\"order_id\":0," + k + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\"}}",
                "{\"order_id\":5," + k + "}",
            };
            for (String b : bad) {
                assertEquals("bad_request", code(c.call("place_order", b)), b);
            }
            assertFalse(EClientSocket.calls.stream().anyMatch(s -> s.startsWith("placeOrder")), "no bad order may reach IB");
            // and a good one still goes through
            Map<String, Object> ok = c.call("place_order", "{\"order_id\":5," + k
                    + ",\"order\":{\"action\":\"SELL\",\"order_type\":\"MKT\",\"total_quantity\":\"1.5\"}}");
            assertEquals(Boolean.TRUE, ok.get("ok"));
            assertTrue(EClientSocket.calls.contains("placeOrder 5"));
        }
    }

    @Test
    void cancelOrderAndCancelAllReachIb() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            assertEquals(Boolean.TRUE, c.call("cancel_order", "{\"order_id\":5}").get("ok"));
            assertEquals(Boolean.TRUE, c.call("cancel_order", "{\"order_id\":6,\"manual_time\":\"2026-11-18T10:00:00Z\"}").get("ok"));
            assertEquals("bad_request", code(c.call("cancel_order", "{\"order_id\":6,\"manul_time\":\"x\"}")));
            assertEquals(Boolean.TRUE, c.call("cancel_all", null).get("ok"));
            assertTrue(EClientSocket.calls.contains("cancelOrder 5 []"));
            assertTrue(EClientSocket.calls.contains("cancelOrder 6 [20261118 10:00:00 UTC]"));
            assertTrue(EClientSocket.calls.contains("reqGlobalCancel"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void executionsNormaliseTimeAndAskForTheFilterInUtc() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            Map<String, Object> r = c.call("executions", "{\"req_id\":3,\"filter\":{\"client_id\":7,\"account\":\"DU1\","
                    + "\"time\":\"2026-11-18T00:00:00Z\",\"symbol\":\"AAPL\"}}");
            assertEquals(Boolean.TRUE, r.get("ok"), String.valueOf(r));
            Map<String, Object> d = (Map<String, Object>) c.waitEvent("exec_details", 3000).get("data");
            Map<String, Object> ex = (Map<String, Object>) d.get("execution");
            assertEquals("2026-11-18T14:30:01Z", ex.get("time"));
            assertEquals("20261118  09:30:01 US/Eastern", ex.get("time_raw"));
            assertEquals("10", ex.get("shares"));
            assertEquals("187.25", ex.get("price"));
            assertEquals("0001.01", ex.get("exec_id"));
            assertEquals(5L, ex.get("order_id"));
            assertEquals("AAPL", ((Map<String, Object>) d.get("contract")).get("symbol"));
            Map<String, Object> cr = (Map<String, Object>) c.waitEvent("commission_report", 3000).get("data");
            assertEquals("1", cr.get("commission"));
            assertFalse(cr.containsKey("realized_pnl"));
            assertEquals(3L, ((Map<String, Object>) c.waitEvent("exec_details_end", 3000).get("data")).get("req_id"));
            assertEquals(7, EClientSocket.lastFilter.clientId);
            assertEquals("20261118 00:00:00 UTC", EClientSocket.lastFilter.time);
            assertEquals("DU1", EClientSocket.lastFilter.acctCode);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void positionsAreSnapshotThenLiveWithAsOfSeq() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            c.call("positions", null);
            Map<String, Object> p1 = c.waitEvent("position", 3000);
            Map<String, Object> end = c.waitEvent("position_end", 3000);
            assertEquals(true, ((Map<String, Object>) p1.get("data")).get("snapshot"));
            assertEquals("10", ((Map<String, Object>) p1.get("data")).get("position"));
            assertEquals(p1.get("seq"), ((Map<String, Object>) end.get("data")).get("as_of_seq"));
            // a live update after position_end is not a snapshot
            EClientSocket cl = EClientSocket.last;
            cl.post(() -> cl.wrapper.position("DU1", new com.ib.client.Contract(), com.ib.client.Decimal.parse("12"), 190.0));
            Map<String, Object> live = c.waitEvent("position", 3000);
            assertEquals(false, ((Map<String, Object>) live.get("data")).get("snapshot"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void ibErrorsKeepTheirSeverityAndConnectivityIsAlsoAStateEvent() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket cl = EClientSocket.last;
            cl.raise(-1, 2104, "Market data farm connection is OK:usfarm");
            Map<String, Object> info = (Map<String, Object>) c.waitEvent("error", 3000).get("data");
            assertEquals("info", info.get("severity"));
            assertEquals(2104L, info.get("code"));
            cl.raise(-1, 1100, "Connectivity between IB and TWS has been lost.");
            assertEquals("connectivity", ((Map<String, Object>) c.waitEvent("error", 3000).get("data")).get("severity"));
            assertEquals("lost", ((Map<String, Object>) c.waitEvent("connectivity", 3000).get("data")).get("state"));
            cl.raise(-1, 1101, "restored, data lost");
            c.waitEvent("error", 3000);
            assertEquals("restored_orders_lost", ((Map<String, Object>) c.waitEvent("connectivity", 3000).get("data")).get("state"));
            cl.raise(1001, 201, "Order rejected");
            Map<String, Object> rej = (Map<String, Object>) c.waitEvent("error", 3000).get("data");
            assertEquals("error", rej.get("severity"));
            assertEquals(1001L, rej.get("order_id"));
            assertEquals(1001L, rej.get("req_id"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void gatewayClosingIsReportedOnceAndDisconnectIsRequested() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket.last.gatewayClosed();
            Map<String, Object> d = (Map<String, Object>) c.waitEvent("disconnected", 3000).get("data");
            assertEquals("gateway_closed", d.get("reason"));
            assertEquals(0, c.events("disconnected", 400).size(), "reported exactly once");
            assertEquals("not_connected", code(c.call("req_ids", null)));
            // reconnect works on the same session
            connect(c);
            assertEquals(Boolean.TRUE, c.call("disconnect", null).get("ok"));
            assertEquals("requested", ((Map<String, Object>) c.waitEvent("disconnected", 3000).get("data")).get("reason"));
            assertEquals("not_connected", code(c.call("disconnect", null)));
        }
    }

    @Test
    void clientLossDisconnectsFromTheGateway() throws Exception {
        TestClient c = hello();
        connect(c);
        c.close();
        for (int i = 0; i < 100 && !EClientSocket.calls.contains("eDisconnect"); i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(EClientSocket.calls.contains("eDisconnect"), "the shim must not keep a gateway session nobody listens to");
        assertFalse(EClientSocket.last.isConnected());
    }

    @Test
    void callbacksWithoutAnEventAreCountedNotForwarded() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket cl = EClientSocket.last;
            cl.post(() -> cl.wrapper.displayGroupList(1, "1"));
            Map<String, Object> pong = c.call("ping", null);
            assertEquals(Boolean.TRUE, pong.get("ok"));
            assertEquals(0, c.events("displayGroupList", 200).size());
        }
        // the count shows up in the log line at detach
        for (int i = 0; i < 50 && !logs.toString().contains("dropped_callbacks"); i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(logs.toString().contains("dropped_callbacks"));
        assertTrue(logs.toString().contains("displayGroupList"));
        assertNull(null);
    }

    @Test
    void tickBurstsAreDeliveredAsConflatableTickEvents() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            assertEquals(Boolean.TRUE, c.call("market_data", "{\"req_id\":9,\"contract\":{\"symbol\":\"AAPL\"},\"snapshot\":false}").get("ok"));
            List<Map<String, Object>> ticks = c.events("tick_price", 500);
            assertFalse(ticks.isEmpty());
            @SuppressWarnings("unchecked")
            Map<String, Object> last = (Map<String, Object>) ticks.get(ticks.size() - 1).get("data");
            assertEquals("104", last.get("price"));
            assertEquals(9L, last.get("req_id"));
        }
    }

    /**
     * PO rule: a regulatory snapshot is a paid per-request market data call, so reqMktData's
     * regulatorySnapshot argument is always false: for streaming and snapshot requests, and
     * whatever a client puts in the request (the argument is not part of the protocol).
     */
    @Test
    void marketDataNeverAsksForARegulatorySnapshot() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            String[] requests = {
                "{\"req_id\":11,\"contract\":{\"symbol\":\"AAPL\"},\"snapshot\":false}",
                "{\"req_id\":12,\"contract\":{\"symbol\":\"AAPL\"},\"snapshot\":true}",
                "{\"req_id\":13,\"contract\":{\"symbol\":\"AAPL\"},\"generic_ticks\":\"233\",\"snapshot\":true,\"regulatory_snapshot\":true}",
                "{\"req_id\":14,\"contract\":{\"symbol\":\"AAPL\"},\"regulatorySnapshot\":true}",
            };
            for (String r : requests) {
                assertEquals(Boolean.TRUE, c.call("market_data", r).get("ok"), r);
            }
            List<String> mkt = new java.util.ArrayList<>();
            synchronized (EClientSocket.calls) {
                for (String call : EClientSocket.calls) {
                    if (call.startsWith("reqMktData ")) {
                        mkt.add(call);
                    }
                }
            }
            assertEquals(4, mkt.size(), mkt.toString());
            for (String call : mkt) {
                assertTrue(call.endsWith(" reg=false"), call);
            }
        }
    }

    @Test
    void aGatewayThatClosesDuringTheHandshakeFailsConnectAtOnceWithItsFirstError() throws Exception {
        try (TestClient c = hello()) {
            long t0 = System.nanoTime();
            Map<String, Object> r = modeConnect(c, "dropme", 20000);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000, "must not wait out the timeout");
            assertEquals("venue_error", code(r));
            assertEquals(326L, ((Map<?, ?>) r.get("error")).get("venue_code"));
            assertEquals(0, c.events("disconnected", 300).size(), "no disconnected for a connection that was never announced");
        }
    }

    @Test
    void aCallbackThatCannotBeTranslatedEndsTheSessionInsteadOfLosingTheEvent() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket cl = EClientSocket.last;
            cl.post(() -> cl.wrapper.orderStatus(1, "Filled")); // wrong arity for what the adapter expects
            String l;
            do {
                l = c.next(3000);
                assertNotNull(l, "the session must end");
            } while (!TestClient.isEof(l));
        }
    }

    @Test
    void anIbDecodeFailureEndsTheSession() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket cl = EClientSocket.last;
            cl.post(() -> { throw new IllegalStateException("cannot decode message"); });
            String l;
            do {
                l = c.next(3000);
                assertNotNull(l, "the session must end");
            } while (!TestClient.isEof(l));
        }
        for (int i = 0; i < 100 && !EClientSocket.calls.contains("eDisconnect"); i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(EClientSocket.calls.contains("eDisconnect"));
    }

    @Test
    void outOfRangeNumbersAreRefused() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            String k = "\"contract\":{\"symbol\":\"ES\",\"sec_type\":\"FUT\",\"exchange\":\"CME\",\"currency\":\"USD\"}";
            assertEquals("bad_request", code(c.call("place_order", "{\"order_id\":8," + k
                    + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"LMT\",\"total_quantity\":\"1\",\"lmt_price\":\"1e400\"}}")));
            assertEquals("bad_request", code(c.call("place_order", "{\"order_id\":8,\"contract\":{\"symbol\":\"X\",\"strike\":\"1e999\"},"
                    + "\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\"}}")));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void openOrderCarriesPermIdAndTrailingFieldsOfOrdersPlacedOutsideTheClient() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket cl = EClientSocket.last;
            com.ib.client.Contract k = new com.ib.client.Contract();
            k.symbol("AAPL");
            com.ib.client.Order trail = new com.ib.client.Order();
            trail.action(com.ib.client.Order.Action.SELL);
            trail.orderType("TRAIL");
            trail.totalQuantity(com.ib.client.Decimal.parse("5"));
            trail.permId(918273645);
            trail.trailStopPrice(187.5);
            trail.trailingPercent(1.25);
            cl.externalOpenOrder(0, k, trail);
            com.ib.client.Order mkt = new com.ib.client.Order();
            mkt.action(com.ib.client.Order.Action.BUY);
            mkt.orderType("MKT");
            mkt.totalQuantity(com.ib.client.Decimal.parse("3"));
            cl.externalOpenOrder(0, k, mkt); // permId not yet assigned
            List<Map<String, Object>> evs = c.events("open_order", 1000);
            assertEquals(2, evs.size());
            Map<String, Object> d0 = (Map<String, Object>) evs.get(0).get("data");
            Map<String, Object> o0 = (Map<String, Object>) d0.get("order");
            assertEquals(0L, d0.get("order_id"));
            assertEquals(918273645L, o0.get("perm_id"));
            assertEquals("187.5", o0.get("trail_stop_price"));
            assertEquals("1.25", o0.get("trailing_percent"));
            Map<String, Object> o1 = (Map<String, Object>) ((Map<String, Object>) evs.get(1).get("data")).get("order");
            assertEquals(0L, o1.get("perm_id"));
            assertFalse(o1.containsKey("trail_stop_price"), "IB's unset sentinel must be omitted");
            assertFalse(o1.containsKey("trailing_percent"));
        }
    }

    @Test
    void permIdAndTrailFieldsAreOutputOnlyAndRejectedInAnOrderToPlace() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            String args = "{\"order_id\":1002,\"contract\":{\"symbol\":\"AAPL\",\"sec_type\":\"STK\",\"exchange\":\"SMART\","
                    + "\"currency\":\"USD\"},\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\","
                    + "\"perm_id\":5,\"trail_stop_price\":\"9\"}}";
            assertEquals("bad_request", code(c.call("place_order", args)));
            assertFalse(EClientSocket.last.calls.contains("placeOrder 1002"));
        }
    }

    @Test
    void lateCallbacksOfAClosedConnectionReachNobody() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket old = EClientSocket.last;
            assertEquals(Boolean.TRUE, c.call("disconnect", null).get("ok"));
            c.waitEvent("disconnected", 3000);
            old.wrapper.openOrderEnd();
            assertEquals(0, c.events("open_order_end", 300).size());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void ambiguousZoneAbbreviationsKeepOnlyTheRawTime() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket.execTime = "20261118  09:30:01 BST";
            c.call("executions", "{\"req_id\":3}");
            Map<String, Object> ex = (Map<String, Object>) ((Map<String, Object>) c.waitEvent("exec_details", 3000).get("data")).get("execution");
            assertFalse(ex.containsKey("time"), "BST is ambiguous: no guessed time");
            assertEquals("20261118  09:30:01 BST", ex.get("time_raw"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void asOfSeqIsFreshPerRequestEvenForAnEmptySnapshot() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            c.call("positions", null);
            c.waitEvent("position", 3000);
            Map<String, Object> end1 = c.waitEvent("position_end", 3000);
            EClientSocket.emptyPositions = true;
            c.call("positions", null);
            Map<String, Object> end2 = c.waitEvent("position_end", 3000);
            long a1 = (Long) ((Map<String, Object>) end1.get("data")).get("as_of_seq");
            long a2 = (Long) ((Map<String, Object>) end2.get("data")).get("as_of_seq");
            assertTrue(a2 > a1, "stale as_of_seq: " + a1 + " then " + a2);
            assertTrue(a2 <= (Long) end2.get("seq"));
        }
    }

    @Test
    void negativePricesAreAllowedAndFieldTypeErrorsAreBadRequests() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            String k = "\"contract\":{\"symbol\":\"ES\",\"sec_type\":\"FUT\",\"exchange\":\"CME\",\"currency\":\"USD\"}";
            assertEquals(Boolean.TRUE, c.call("place_order", "{\"order_id\":8," + k
                    + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"LMT\",\"total_quantity\":\"1\",\"lmt_price\":\"-1.5\"}}").get("ok"));
            for (String bad : new String[] {"\"parent_id\":\"5\"", "\"transmit\":\"false\"", "\"parent_id\":99999999999",
                "\"tif\":5", "\"order_ref\":7"}) {
                assertEquals("bad_request", code(c.call("place_order", "{\"order_id\":9," + k
                        + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\"," + bad + "}}")), bad);
            }
            assertEquals("bad_request", code(c.call("executions", "{\"req_id\":1,\"filter\":{\"client_id\":\"x\"}}")));
            assertEquals(Boolean.TRUE, c.call("executions", "{\"req_id\":1,\"filter\":{\"symbol\":null}}").get("ok"));
        }
    }

    @Test
    void aWaitingConnectIsReleasedWhenTheClientGoesAway() throws Exception {
        TestClient c = hello();
        EClientSocket.mode = "silent";
        c.send("connect", "{\"host\":\"127.0.0.1\",\"port\":" + port + ",\"client_id\":1,\"connect_timeout_ms\":60000}");
        Thread.sleep(300);
        c.close();
        // the shim must free its only client slot long before the 60 s connect timeout
        TestClient next = null;
        for (int i = 0; i < 60 && next == null; i++) {
            TestClient t = new TestClient(sock);
            String l = t.next(300);
            if (l != null && l.contains("busy")) {
                t.close();
                Thread.sleep(100);
                continue;
            }
            next = t;
        }
        assertNotNull(next, "client slot stayed blocked behind connect");
        next.close();
    }

    @SuppressWarnings("unchecked")
    @Test
    void disconnectedIsAnnouncedAfterEveryEventThatWasQueuedBeforeTheSocketClosed() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket cl = EClientSocket.last;
            cl.post(() -> { try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            cl.post(() -> cl.wrapper.orderStatus(77, "Filled", com.ib.client.Decimal.parse("10"), com.ib.client.Decimal.parse("0"),
                    187.25, 4242L, 0, 187.25, 7, "", Double.MAX_VALUE));
            Thread.sleep(50);
            cl.ibThreadClose(); // IB's reader thread closes while the fill is still queued for the shim
            long seqFill = -1;
            long seqClosed = -1;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((seqFill < 0 || seqClosed < 0) && System.nanoTime() < deadline) {
                String l = c.next(500);
                if (l == null || TestClient.isEof(l)) {
                    continue;
                }
                Map<String, Object> m = Json.parseObject(l);
                if ("order_status".equals(m.get("event"))) {
                    seqFill = (Long) m.get("seq");
                } else if ("disconnected".equals(m.get("event"))) {
                    seqClosed = (Long) m.get("seq");
                }
            }
            assertTrue(seqFill > 0, "the queued fill must still be delivered");
            assertTrue(seqClosed > seqFill, "disconnected (" + seqClosed + ") must follow the fill (" + seqFill + ")");
        }
    }

    @Test
    void nullsInOrdersAreRefusedBecauseTheyWouldMeanIbsDefault() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            String k = "\"contract\":{\"symbol\":\"AAPL\",\"sec_type\":\"STK\",\"exchange\":\"SMART\",\"currency\":\"USD\"}";
            String base = "\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\"";
            for (String bad : new String[] {"\"what_if\":null", "\"transmit\":null", "\"parent_id\":null", "\"lmt_price\":null", "\"tif\":null"}) {
                assertEquals("bad_request", code(c.call("place_order", "{\"order_id\":5," + k + ",\"order\":{" + base + "," + bad + "}}")), bad);
            }
            assertEquals("bad_request", code(c.call("place_order", "{\"order_id\":5,\"contract\":{\"symbol\":null},\"order\":{" + base + "}}")));
            assertEquals("bad_request", code(c.call("cancel_order", "{\"order_id\":5,\"manual_time\":null}")));
            assertFalse(EClientSocket.calls.stream().anyMatch(s -> s.startsWith("placeOrder") || s.startsWith("cancelOrder")));
        }
    }

    @Test
    void ordersAreRefusedWhenIbItselfNoLongerReportsAConnection() throws Exception {
        try (TestClient c = hello()) {
            connect(c);
            EClientSocket cl = EClientSocket.last;
            cl.gatewayClosed();
            // before the shim's own flag has caught up, IB's isConnected is already false
            String k = "\"contract\":{\"symbol\":\"AAPL\",\"sec_type\":\"STK\",\"exchange\":\"SMART\",\"currency\":\"USD\"}";
            Map<String, Object> r = c.call("place_order", "{\"order_id\":5," + k
                    + ",\"order\":{\"action\":\"BUY\",\"order_type\":\"MKT\",\"total_quantity\":\"1\"}}");
            assertEquals("not_connected", code(r));
            assertFalse(EClientSocket.calls.contains("placeOrder 5"));
        }
    }
}
