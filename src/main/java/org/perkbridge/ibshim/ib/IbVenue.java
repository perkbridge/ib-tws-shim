// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim.ib;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.perkbridge.ibshim.Log;
import org.perkbridge.ibshim.OpException;
import org.perkbridge.ibshim.Sink;
import org.perkbridge.ibshim.Venue;

/**
 * The IB venue: translates protocol ops into calls on IB's official Java
 * client and the client's callbacks into protocol events, one to one. It holds
 * no credentials and no state beyond the one gateway connection of the current
 * client. IB's classes are reached by name through {@link Reflect}; none of
 * them is copied into this repository or the shim jar.
 */
public final class IbVenue implements Venue {
    private static final List<String> OPS = List.of(
            "connect", "disconnect", "req_ids", "server_time", "contract_details", "place_order",
            "cancel_order", "cancel_all", "open_orders", "completed_orders", "executions",
            "positions", "cancel_positions", "account_summary", "cancel_account_summary",
            "account_updates", "market_data", "cancel_market_data", "market_data_type",
            "historical_data", "cancel_historical_data", "market_rule", "pnl_single",
            "cancel_pnl_single");

    private static final Set<String> CONTRACT_KEYS = Set.of("con_id", "symbol", "sec_type", "exchange",
            "primary_exchange", "currency", "local_symbol", "trading_class", "last_trade_date", "strike",
            "right", "multiplier");
    private static final Set<String> ORDER_KEYS = Set.of("action", "order_type", "total_quantity", "lmt_price",
            "aux_price", "tif", "order_ref", "account", "oca_group", "oca_type", "parent_id", "transmit",
            "outside_rth", "good_till_date", "what_if");
    private static final Set<String> ORDER_TYPES = Set.of("MKT", "LMT", "STP", "STP LMT");
    private static final Set<String> TIFS = Set.of("DAY", "GTC", "GTD", "IOC", "FOK");

    private final Log log;
    private final ClassLoader cl;

    // one connection at a time; guarded by this
    private Object client;
    private Object signal;
    private Object reader;
    private Thread readerThread;
    private volatile Sink sink;
    private volatile boolean connected;
    private volatile Conn conn;

    private final Map<String, AtomicLong> dropped = new ConcurrentHashMap<>();

    /** Per-connection state touched by callbacks. */
    private static final class Conn {
        final CountDownLatch nextId = new CountDownLatch(1);
        final CountDownLatch accounts = new CountDownLatch(1);
        volatile long nextValidId = -1;
        volatile List<Object> accountList = List.of();
        volatile int lastErrorCode;
        volatile String lastErrorMessage = "";
        volatile boolean userDisconnect;
        volatile boolean disconnectedSent;
        volatile boolean positionsSnapshot;
        volatile long lastPositionSeq;
        final Map<Long, long[]> accountSummary = new ConcurrentHashMap<>(); // req_id -> {snapshot, lastSeq}
    }

    public IbVenue(Log log) {
        this.log = log;
        this.cl = IbVenue.class.getClassLoader();
    }

    private Class<?> cls(String name) {
        try {
            return Class.forName(name, true, cl);
        } catch (ClassNotFoundException e) {
            throw new Reflect.MissingMemberException("IB class " + name + " not found in the API jar");
        }
    }

    // ---------------------------------------------------------------- Venue

    @Override
    public String apiClientVersion() {
        Class<?> c = cls("com.ib.client.EClientSocket");
        Package p = c.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        if (v != null && !v.isEmpty()) {
            return v;
        }
        try {
            Object cv = c.getField("CLIENT_VERSION").get(null);
            if (cv != null) {
                return "client_version=" + cv;
            }
        } catch (ReflectiveOperationException ignored) {
            // no constant offered
        }
        return "unknown";
    }

    @Override
    public List<String> ops() {
        return OPS;
    }

    @Override
    public boolean connected() {
        Object c = client;
        return connected && c != null && Boolean.TRUE.equals(safeIsConnected(c));
    }

    private static Boolean safeIsConnected(Object c) {
        try {
            return (Boolean) Reflect.call(c, "isConnected");
        } catch (RuntimeException e) {
            return Boolean.FALSE;
        }
    }

    @Override
    public void attach(Sink s) {
        this.sink = s;
    }

    @Override
    public synchronized void detach() {
        Sink s = sink;
        sink = null;
        teardown(false);
        logDropped("detach");
        if (s != null) {
            log.debug("venue_detached");
        }
    }

    private void emit(String name, Map<String, Object> data) {
        Sink s = sink;
        if (s != null) {
            s.event(name, data);
        }
    }

    private long emitSeq(String name, Map<String, Object> data) {
        Sink s = sink;
        return s == null ? 0 : s.event(name, data);
    }

    // ---------------------------------------------------------------- ops

    @Override
    public synchronized Map<String, Object> handle(String op, Map<String, Object> a) throws OpException {
        try {
            return dispatch(op, a);
        } catch (Reflect.MissingMemberException e) {
            log.error("api_mismatch", "op", op, "error", e.getMessage());
            throw new OpException(OpException.UNSUPPORTED_OP,
                    "the loaded IB client library does not offer what " + op + " needs: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new OpException(OpException.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("api_call_failed", "op", op, "error", String.valueOf(e.getCause() == null ? e : e.getCause()));
            throw new OpException(OpException.VENUE_ERROR, "the IB client rejected " + op + ": "
                    + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        }
    }

    private Map<String, Object> dispatch(String op, Map<String, Object> a) throws OpException {
        switch (op) {
            case "connect":
                return connect(a);
            case "disconnect": {
                Conn c = requireConn();
                c.userDisconnect = true;
                teardown(true);
                return empty();
            }
            case "req_ids":
                requireConn();
                call("reqIds", -1L);
                return empty();
            case "server_time":
                requireConn();
                call("reqCurrentTime");
                return empty();
            case "contract_details":
                requireConn();
                call("reqContractDetails", reqId(a), contract(obj(a, "contract", true), false));
                return empty();
            case "place_order":
                return placeOrder(a);
            case "cancel_order":
                return cancelOrder(a);
            case "cancel_all":
                requireConn();
                try {
                    call("reqGlobalCancel");
                } catch (Reflect.MissingMemberException e) {
                    call("reqGlobalCancel", newOf("com.ib.client.OrderCancel"));
                }
                return empty();
            case "open_orders":
                requireConn();
                call(bool(a, "all", false) ? "reqAllOpenOrders" : "reqOpenOrders");
                return empty();
            case "completed_orders":
                requireConn();
                call("reqCompletedOrders", bool(a, "api_only", false));
                return empty();
            case "executions":
                requireConn();
                call("reqExecutions", reqId(a), execFilter(obj(a, "filter", false)));
                return empty();
            case "positions":
                conn0().positionsSnapshot = true;
                call("reqPositions");
                return empty();
            case "cancel_positions":
                requireConn();
                call("cancelPositions");
                return empty();
            case "account_summary": {
                requireConn();
                long id = reqId(a);
                conn0().accountSummary.put(id, new long[] {1, 0});
                call("reqAccountSummary", id, str(a, "group", true), str(a, "tags", true));
                return empty();
            }
            case "cancel_account_summary": {
                requireConn();
                long id = reqId(a);
                conn0().accountSummary.remove(id);
                call("cancelAccountSummary", id);
                return empty();
            }
            case "account_updates":
                requireConn();
                call("reqAccountUpdates", bool(a, "subscribe", true), str(a, "account", false) == null ? "" : str(a, "account", false));
                return empty();
            case "market_data":
                requireConn();
                call("reqMktData", reqId(a), contract(obj(a, "contract", true), false),
                        str(a, "generic_ticks", false) == null ? "" : str(a, "generic_ticks", false),
                        bool(a, "snapshot", false), Boolean.FALSE, new ArrayList<Object>());
                return empty();
            case "cancel_market_data":
                requireConn();
                call("cancelMktData", reqId(a));
                return empty();
            case "market_data_type":
                requireConn();
                call("reqMarketDataType", intIn(a, "type", 1, 4));
                return empty();
            case "historical_data":
                requireConn();
                return historical(a);
            case "cancel_historical_data":
                requireConn();
                call("cancelHistoricalData", reqId(a));
                return empty();
            case "market_rule":
                requireConn();
                call("reqMarketRule", intIn(a, "market_rule_id", 0, Integer.MAX_VALUE));
                return empty();
            case "pnl_single":
                requireConn();
                call("reqPnLSingle", reqId(a), str(a, "account", true),
                        str(a, "model_code", false) == null ? "" : str(a, "model_code", false),
                        intIn(a, "con_id", 1, Integer.MAX_VALUE));
                return empty();
            case "cancel_pnl_single":
                requireConn();
                call("cancelPnLSingle", reqId(a));
                return empty();
            default:
                throw new OpException(OpException.UNSUPPORTED_OP, "unknown op " + op);
        }
    }

    private static Map<String, Object> empty() {
        return new LinkedHashMap<>();
    }

    private Conn conn0() throws OpException {
        return requireConn();
    }

    private Conn requireConn() throws OpException {
        Conn c = conn;
        if (c == null || client == null || !connected) {
            throw new OpException(OpException.NOT_CONNECTED, "not connected to the gateway");
        }
        return c;
    }

    private Object call(String method, Object... args) {
        return Reflect.call(client, method, args);
    }

    private Object newOf(String className) {
        return Reflect.newInstance(cls(className));
    }

    // ---------------------------------------------------------------- connect

    private Map<String, Object> connect(Map<String, Object> a) throws OpException {
        if (client != null && connected) {
            throw new OpException(OpException.ALREADY_CONNECTED, "already connected to a gateway");
        }
        teardown(false);
        String host = str(a, "host", true);
        int port = intIn(a, "port", 1, 65535);
        int clientId = intIn(a, "client_id", 0, Integer.MAX_VALUE);
        int timeoutMs = a.containsKey("connect_timeout_ms") ? intIn(a, "connect_timeout_ms", 100, 300_000) : 10_000;

        Conn c = new Conn();
        conn = c;
        Object wrapper = newWrapper(c);
        signal = newOf("com.ib.client.EJavaSignal");
        client = Reflect.newInstance(cls("com.ib.client.EClientSocket"), wrapper, signal);
        connected = false;

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            Reflect.call(client, "eConnect", host, (long) port, (long) clientId);
        } catch (RuntimeException e) {
            teardown(false);
            throw new OpException(OpException.VENUE_ERROR, "could not connect to " + host + ":" + port + ": " + e.getMessage());
        }
        if (!Boolean.TRUE.equals(safeIsConnected(client))) {
            int code = c.lastErrorCode;
            String msg = c.lastErrorMessage;
            teardown(false);
            throw new OpException(OpException.VENUE_ERROR,
                    "could not connect to " + host + ":" + port + (msg.isEmpty() ? "" : ": " + msg), code);
        }
        connected = true;
        reader = Reflect.newInstance(cls("com.ib.client.EReader"), client, signal);
        Reflect.call(reader, "start");
        final Object cl0 = client, sig0 = signal, rd0 = reader;
        readerThread = new Thread(() -> readerLoop(cl0, sig0, rd0, c), "ibshim-ib-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        try {
            if (!c.nextId.await(Math.max(1, remainingMs(deadline)), TimeUnit.MILLISECONDS)) {
                int code = c.lastErrorCode;
                teardown(false);
                throw new OpException(OpException.TIMEOUT,
                        "the gateway accepted the socket but sent no next valid id within " + timeoutMs + " ms", code);
            }
            c.accounts.await(Math.min(2000, Math.max(1, remainingMs(deadline))), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            teardown(false);
            throw new OpException(OpException.INTERNAL, "interrupted while connecting");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        Object sv = Reflect.call(client, "serverVersion");
        r.put("server_version", sv instanceof Number n ? n.longValue() : 0L);
        String ct = String.valueOf(Reflect.call(client, "getTwsConnectionTime"));
        String ctNorm = Times.toRfc3339(ct);
        r.put("connection_time", ctNorm != null ? ctNorm : ct);
        r.put("next_valid_id", c.nextValidId);
        r.put("accounts", new ArrayList<>(c.accountList));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("server_version", r.get("server_version"));
        ev.put("connection_time", r.get("connection_time"));
        ev.put("client_id", (long) clientId);
        emit("connected", ev);
        log.info("gateway_connected", "host", host, "port", port, "client_id", clientId);
        return r;
    }

    private static long remainingMs(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    private void readerLoop(Object cl0, Object sig0, Object rd0, Conn c) {
        try {
            while (Boolean.TRUE.equals(safeIsConnected(cl0))) {
                Reflect.call(sig0, "waitForSignal");
                try {
                    Reflect.call(rd0, "processMsgs");
                } catch (RuntimeException e) {
                    log.warn("ib_reader_error", "error", e.toString());
                    onCallback(c, "error", new Object[] {new IllegalStateException(e.toString())});
                }
            }
        } catch (RuntimeException e) {
            log.warn("ib_reader_stopped", "error", e.toString());
        }
        // the socket is gone: tell the client once, unless it asked for this
        if (!c.userDisconnect && conn == c) {
            connectionClosed(c, "gateway_closed", "gateway socket closed");
        }
    }

    /** Disconnects from the gateway and drops per-connection state. */
    private void teardown(boolean emitRequested) {
        Object c = client;
        Conn conn0 = conn;
        if (c != null) {
            try {
                Reflect.call(c, "eDisconnect");
            } catch (RuntimeException e) {
                log.debug("disconnect_failed", "error", e.toString());
            }
        }
        if (emitRequested && conn0 != null && !conn0.disconnectedSent) {
            conn0.disconnectedSent = true;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("reason", "requested");
            emit("disconnected", d);
        }
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
        }
        client = null;
        signal = null;
        reader = null;
        readerThread = null;
        connected = false;
        if (!emitRequested) {
            conn = null;
        }
    }

    private void connectionClosed(Conn c, String reason, String message) {
        if (c.disconnectedSent) {
            return;
        }
        c.disconnectedSent = true;
        connected = false;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", reason);
        if (message != null && !message.isEmpty()) {
            d.put("message", message);
        }
        emit("disconnected", d);
        logDropped("disconnected");
    }

    // ---------------------------------------------------------------- orders

    private Map<String, Object> placeOrder(Map<String, Object> a) throws OpException {
        requireConn();
        strict(a, Set.of("order_id", "contract", "order"), "place_order args");
        int orderId = intIn(a, "order_id", 1, Integer.MAX_VALUE);
        Map<String, Object> o = obj(a, "order", true);
        strict(o, ORDER_KEYS, "order");
        Object contract = contract(obj(a, "contract", true), true);
        Object order = newOf("com.ib.client.Order");

        String action = reqStr(o, "action");
        if (!action.equals("BUY") && !action.equals("SELL")) {
            throw new IllegalArgumentException("order.action must be BUY or SELL");
        }
        String type = reqStr(o, "order_type");
        if (!ORDER_TYPES.contains(type)) {
            throw new IllegalArgumentException("order.order_type must be one of " + new java.util.TreeSet<>(ORDER_TYPES));
        }
        decimal(o, "total_quantity", true, true);
        if ((type.equals("LMT") || type.equals("STP LMT")) && !o.containsKey("lmt_price")) {
            throw new IllegalArgumentException("order.lmt_price is required for " + type);
        }
        if ((type.equals("STP") || type.equals("STP LMT")) && !o.containsKey("aux_price")) {
            throw new IllegalArgumentException("order.aux_price (the stop price) is required for " + type);
        }
        decimal(o, "lmt_price", false, false);
        decimal(o, "aux_price", false, false);
        if (o.containsKey("tif")) {
            String tif = reqStr(o, "tif");
            if (!TIFS.contains(tif)) {
                throw new IllegalArgumentException("order.tif must be one of " + new java.util.TreeSet<>(TIFS));
            }
            if (tif.equals("GTD") && !o.containsKey("good_till_date")) {
                throw new IllegalArgumentException("order.good_till_date is required for GTD");
            }
        }
        if (o.containsKey("oca_type")) {
            intIn(o, "oca_type", 0, 3);
        }
        Map<String, Object> copy = new LinkedHashMap<>(o);
        if (copy.containsKey("good_till_date")) {
            copy.put("good_till_date", Times.toIb(reqStr(copy, "good_till_date")));
        }
        Mapper.in(copy, Mapper.ORDER, order);
        call("placeOrder", (long) orderId, contract, order);
        return empty();
    }

    private Map<String, Object> cancelOrder(Map<String, Object> a) throws OpException {
        requireConn();
        strict(a, Set.of("order_id", "manual_time"), "cancel_order args");
        int id = intIn(a, "order_id", 1, Integer.MAX_VALUE);
        String manual = str(a, "manual_time", false);
        String ib = manual == null ? "" : Times.toIb(manual);
        try {
            call("cancelOrder", (long) id, ib);
        } catch (Reflect.MissingMemberException e) {
            Object oc = newOf("com.ib.client.OrderCancel");
            if (manual != null) {
                Reflect.set(oc, "manualOrderCancelTime", ib);
            }
            call("cancelOrder", (long) id, oc);
        }
        return empty();
    }

    private Object contract(Map<String, Object> c, boolean strictKeys) {
        if (strictKeys) {
            strict(c, CONTRACT_KEYS, "contract");
        }
        Object k = newOf("com.ib.client.Contract");
        Mapper.in(c, Mapper.CONTRACT, k);
        return k;
    }

    private Object execFilter(Map<String, Object> f) {
        Object ef = newOf("com.ib.client.ExecutionFilter");
        if (f == null) {
            return ef;
        }
        Map<String, Object> g = new LinkedHashMap<>(f);
        if (g.containsKey("time") && !str0(g, "time").isEmpty()) {
            g.put("time", Times.toIb(str0(g, "time")));
        }
        String[][] map = {{"account", "acctCode"}, {"time", "time"}, {"symbol", "symbol"}, {"sec_type", "secType"},
            {"exchange", "exchange"}, {"side", "side"}};
        if (g.containsKey("client_id")) {
            Reflect.set(ef, "clientId", g.get("client_id"));
        }
        for (String[] m : map) {
            if (g.containsKey(m[0])) {
                Reflect.set(ef, m[1], g.get(m[0]).toString());
            }
        }
        return ef;
    }

    private Map<String, Object> historical(Map<String, Object> a) throws OpException {
        long id = reqId(a);
        Object contract = contract(obj(a, "contract", true), false);
        String end = str(a, "end_time", false);
        String endIb = end == null || end.isEmpty() ? "" : Times.toIb(end);
        boolean keepUp = bool(a, "keep_up_to_date", false);
        if (keepUp && !endIb.isEmpty()) {
            throw new IllegalArgumentException("keep_up_to_date needs an empty end_time");
        }
        call("reqHistoricalData", id, contract, endIb, str(a, "duration", true), str(a, "bar_size", true),
                str(a, "what_to_show", true), bool(a, "use_rth", false) ? 1L : 0L, 2L, keepUp, new ArrayList<Object>());
        return empty();
    }

    // ---------------------------------------------------------------- callbacks

    private Object newWrapper(Conn c) {
        Class<?> iface = cls("com.ib.client.EWrapper");
        InvocationHandler h = (proxy, method, args) -> {
            Object[] ar = args == null ? new Object[0] : args;
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == ar[0];
                case "toString":
                    return "ib-tws-shim EWrapper";
                default:
                    break;
            }
            try {
                onCallback(c, method.getName(), ar);
            } catch (RuntimeException e) {
                log.error("callback_failed", "callback", method.getName(), "error", e.toString());
            }
            return defaultValue(method);
        };
        return Proxy.newProxyInstance(cl, new Class<?>[] {iface}, h);
    }

    private static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == double.class) return 0.0;
        return null;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static String s(Object o) {
        return o == null ? "" : o.toString();
    }

    private static void putDec(Map<String, Object> m, String key, Object v) {
        if (!Mapper.unset(v)) {
            m.put(key, Mapper.decimalString(v));
        }
    }

    private void onCallback(Conn c, String name, Object[] ar) {
        Map<String, Object> d = new LinkedHashMap<>();
        switch (name) {
            case "connectAck" -> { /* handshake is the connect op's business */ }
            case "nextValidId" -> {
                c.nextValidId = num(ar[0]);
                c.nextId.countDown();
                d.put("order_id", num(ar[0]));
                emit("next_valid_id", d);
            }
            case "managedAccounts" -> {
                List<Object> accts = new ArrayList<>();
                for (String p : s(ar[0]).split(",")) {
                    if (!p.trim().isEmpty()) {
                        accts.add(p.trim());
                    }
                }
                c.accountList = accts;
                c.accounts.countDown();
                d.put("accounts", accts);
                emit("managed_accounts", d);
            }
            case "currentTime" -> {
                d.put("time", Times.epochToRfc3339(num(ar[0])));
                d.put("epoch", num(ar[0]));
                emit("current_time", d);
            }
            case "connectionClosed" -> {
                if (!c.userDisconnect) {
                    connectionClosed(c, "gateway_closed", "gateway socket closed");
                }
            }
            case "error" -> onError(c, ar);
            case "openOrder" -> {
                d.put("order_id", num(ar[0]));
                d.put("contract", Mapper.out(ar[1], Mapper.CONTRACT));
                d.put("order", Mapper.out(ar[2], Mapper.ORDER));
                d.put("order_state", Mapper.out(ar[3], Mapper.ORDER_STATE));
                emit("open_order", d);
            }
            case "openOrderEnd" -> emit("open_order_end", d);
            case "completedOrder" -> {
                d.put("contract", Mapper.out(ar[0], Mapper.CONTRACT));
                d.put("order", Mapper.out(ar[1], Mapper.ORDER));
                d.put("order_state", Mapper.out(ar[2], Mapper.ORDER_STATE));
                emit("completed_order", d);
            }
            case "completedOrdersEnd" -> emit("completed_orders_end", d);
            case "orderStatus" -> {
                d.put("order_id", num(ar[0]));
                d.put("status", s(ar[1]));
                d.put("filled", Mapper.decimalString(ar[2]));
                d.put("remaining", Mapper.decimalString(ar[3]));
                putDec(d, "avg_fill_price", ar[4]);
                d.put("perm_id", num(ar[5]));
                d.put("parent_id", num(ar[6]));
                putDec(d, "last_fill_price", ar[7]);
                d.put("client_id", num(ar[8]));
                if (!s(ar[9]).isEmpty()) {
                    d.put("why_held", s(ar[9]));
                }
                putDec(d, "mkt_cap_price", ar[10]);
                emit("order_status", d);
            }
            case "execDetails" -> {
                d.put("req_id", num(ar[0]));
                d.put("contract", Mapper.out(ar[1], Mapper.CONTRACT));
                Map<String, Object> ex = Mapper.out(ar[2], Mapper.EXECUTION);
                String raw = s(Reflect.get(ar[2], "time"));
                if (!raw.isEmpty()) {
                    String norm = Times.toRfc3339(raw);
                    if (norm != null) {
                        ex.put("time", norm);
                    }
                    ex.put("time_raw", raw);
                }
                d.put("execution", ex);
                emit("exec_details", d);
            }
            case "execDetailsEnd" -> {
                d.put("req_id", num(ar[0]));
                emit("exec_details_end", d);
            }
            case "commissionReport", "commissionAndFeesReport" -> {
                emit("commission_report", Mapper.out(ar[0], Mapper.COMMISSION));
            }
            case "position" -> {
                d.put("account", s(ar[0]));
                d.put("contract", Mapper.out(ar[1], Mapper.CONTRACT));
                d.put("position", Mapper.decimalString(ar[2]));
                putDec(d, "avg_cost", ar[3]);
                d.put("snapshot", c.positionsSnapshot);
                long sq = emitSeq("position", d);
                if (c.positionsSnapshot) {
                    c.lastPositionSeq = sq;
                }
            }
            case "positionEnd" -> {
                d.put("as_of_seq", c.lastPositionSeq);
                c.positionsSnapshot = false;
                emit("position_end", d);
            }
            case "accountSummary" -> {
                long id = num(ar[0]);
                long[] st = c.accountSummary.get(id);
                d.put("req_id", id);
                d.put("account", s(ar[1]));
                d.put("tag", s(ar[2]));
                d.put("value", s(ar[3]));
                if (!s(ar[4]).isEmpty()) {
                    d.put("currency", s(ar[4]));
                }
                boolean snap = st != null && st[0] == 1;
                d.put("snapshot", snap);
                long sq = emitSeq("account_summary", d);
                if (snap) {
                    st[1] = sq;
                }
            }
            case "accountSummaryEnd" -> {
                long id = num(ar[0]);
                long[] st = c.accountSummary.get(id);
                d.put("req_id", id);
                d.put("as_of_seq", st == null ? 0L : st[1]);
                if (st != null) {
                    st[0] = 0;
                }
                emit("account_summary_end", d);
            }
            case "updateAccountValue" -> {
                d.put("key", s(ar[0]));
                d.put("value", s(ar[1]));
                d.put("currency", s(ar[2]));
                d.put("account", s(ar[3]));
                emit("account_value", d);
            }
            case "updatePortfolio" -> {
                d.put("contract", Mapper.out(ar[0], Mapper.CONTRACT));
                d.put("position", Mapper.decimalString(ar[1]));
                putDec(d, "market_price", ar[2]);
                putDec(d, "market_value", ar[3]);
                putDec(d, "average_cost", ar[4]);
                putDec(d, "unrealized_pnl", ar[5]);
                putDec(d, "realized_pnl", ar[6]);
                d.put("account", s(ar[7]));
                emit("portfolio", d);
            }
            case "updateAccountTime" -> {
                d.put("time_raw", s(ar[0]));
                emit("account_time", d);
            }
            case "accountDownloadEnd" -> {
                d.put("account", s(ar[0]));
                emit("account_download_end", d);
            }
            case "contractDetails" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("req_id", num(ar[0]));
                m.put("contract", Mapper.out(Reflect.get(ar[1], "contract"), Mapper.CONTRACT));
                m.putAll(Mapper.out(ar[1], Mapper.CONTRACT_DETAILS));
                emit("contract_details", m);
            }
            case "contractDetailsEnd" -> {
                d.put("req_id", num(ar[0]));
                emit("contract_details_end", d);
            }
            case "marketRule" -> {
                d.put("market_rule_id", num(ar[0]));
                List<Object> inc = new ArrayList<>();
                if (ar[1] instanceof Object[] arr) {
                    for (Object p : arr) {
                        Map<String, Object> e = new LinkedHashMap<>();
                        putDec(e, "low_edge", Reflect.get(p, "lowEdge"));
                        putDec(e, "increment", Reflect.get(p, "increment"));
                        inc.add(e);
                    }
                }
                d.put("increments", inc);
                emit("market_rule", d);
            }
            case "historicalData", "historicalDataUpdate" -> {
                d.put("req_id", num(ar[0]));
                String raw = s(Reflect.get(ar[1], "time"));
                String norm = Times.toRfc3339(raw);
                if (norm != null) {
                    d.put("time", norm);
                }
                d.put("time_raw", raw);
                d.putAll(Mapper.out(ar[1], Mapper.BAR));
                d.put("update", name.equals("historicalDataUpdate"));
                emit("historical_bar", d);
            }
            case "historicalDataEnd" -> {
                d.put("req_id", num(ar[0]));
                d.put("start_raw", s(ar[1]));
                d.put("end_raw", s(ar[2]));
                emit("historical_data_end", d);
            }
            case "pnlSingle" -> {
                d.put("req_id", num(ar[0]));
                putDec(d, "position", ar[1]);
                putDec(d, "daily_pnl", ar[2]);
                putDec(d, "unrealized_pnl", ar[3]);
                putDec(d, "realized_pnl", ar[4]);
                putDec(d, "value", ar[5]);
                emit("pnl_single", d);
            }
            case "tickPrice" -> {
                d.put("req_id", num(ar[0]));
                d.put("tick_type", num(ar[1]));
                d.put("price", Mapper.decimalString(ar[2]));
                Map<String, Object> at = new LinkedHashMap<>();
                for (String k : new String[] {"canAutoExecute", "pastLimit", "preOpen"}) {
                    Object v = Reflect.get(ar.length > 3 ? ar[3] : null, k);
                    if (v instanceof Boolean) {
                        at.put(k.equals("canAutoExecute") ? "can_auto_execute" : k.equals("pastLimit") ? "past_limit" : "pre_open", v);
                    }
                }
                d.put("attrib", at);
                emit("tick_price", d);
            }
            case "tickSize" -> {
                d.put("req_id", num(ar[0]));
                d.put("tick_type", num(ar[1]));
                d.put("size", Mapper.decimalString(ar[2]));
                emit("tick_size", d);
            }
            case "tickString" -> {
                d.put("req_id", num(ar[0]));
                d.put("tick_type", num(ar[1]));
                d.put("value", s(ar[2]));
                emit("tick_string", d);
            }
            case "tickGeneric" -> {
                d.put("req_id", num(ar[0]));
                d.put("tick_type", num(ar[1]));
                d.put("value", Mapper.decimalString(ar[2]));
                emit("tick_generic", d);
            }
            case "tickSnapshotEnd" -> {
                d.put("req_id", num(ar[0]));
                emit("tick_snapshot_end", d);
            }
            default -> dropped.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
        }
    }

    private void onError(Conn c, Object[] ar) {
        Map<String, Object> d = new LinkedHashMap<>();
        long id = -1;
        int code = -1;
        String msg;
        String adv = "";
        if (ar.length >= 4 && ar[0] instanceof Number) {
            // error(id, [errorTime,] code, message, advancedOrderRejectJson)
            id = num(ar[0]);
            int base = ar.length >= 5 ? 2 : 1;
            code = (int) num(ar[base]);
            msg = s(ar[base + 1]);
            adv = ar.length > base + 2 ? s(ar[base + 2]) : "";
        } else if (ar.length == 3 && ar[0] instanceof Number) {
            id = num(ar[0]);
            code = (int) num(ar[1]);
            msg = s(ar[2]);
        } else if (ar.length >= 1 && ar[0] instanceof Throwable t) {
            msg = t.toString();
        } else {
            msg = ar.length == 0 ? "" : s(ar[0]);
        }
        c.lastErrorCode = code;
        c.lastErrorMessage = msg;
        d.put("req_id", id);
        d.put("order_id", id);
        d.put("code", (long) code);
        d.put("message", msg);
        d.put("severity", severity(code));
        if (!adv.isEmpty()) {
            d.put("advanced_order_reject", adv);
        }
        emit("error", d);
        String state = switch (code) {
            case 1100 -> "lost";
            case 1101 -> "restored_orders_lost";
            case 1102 -> "restored_orders_kept";
            default -> null;
        };
        if (state != null) {
            Map<String, Object> cd = new LinkedHashMap<>();
            cd.put("state", state);
            emit("connectivity", cd);
        }
    }

    static String severity(int code) {
        if (code >= 1100 && code <= 1102) {
            return "connectivity";
        }
        if (code >= 2100 && code <= 2169) {
            return "info";
        }
        return "error";
    }

    private void logDropped(String when) {
        if (dropped.isEmpty()) {
            return;
        }
        Map<String, Object> m = new TreeMap<>();
        dropped.forEach((k, v) -> m.put(k, v.get()));
        log.info("dropped_callbacks", "when", when, "counts", org.perkbridge.ibshim.Json.write(m));
    }

    // ---------------------------------------------------------------- argument helpers

    private static void strict(Map<String, Object> m, Set<String> allowed, String what) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException("unknown field '" + k + "' in " + what);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> m, String key, boolean required) {
        Object v = m.get(key);
        if (v == null) {
            if (required) {
                throw new IllegalArgumentException(key + " is required");
            }
            return null;
        }
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return (Map<String, Object>) v;
    }

    private static String str0(Map<String, Object> m, String key) {
        return String.valueOf(m.get(key));
    }

    private static String str(Map<String, Object> m, String key, boolean required) {
        Object v = m.get(key);
        if (v == null) {
            if (required) {
                throw new IllegalArgumentException(key + " is required");
            }
            return null;
        }
        if (!(v instanceof String)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return (String) v;
    }

    private static String reqStr(Map<String, Object> m, String key) {
        String s = str(m, key, true);
        if (s.isEmpty()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
        return s;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean required) {
        Object v = m.get(key);
        if (v == null) {
            if (required) {
                throw new IllegalArgumentException(key + " is required");
            }
            return false;
        }
        if (!(v instanceof Boolean)) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return (Boolean) v;
    }

    private static int intIn(Map<String, Object> m, String key, long min, long max) {
        Object v = m.get(key);
        if (!(v instanceof Long l)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        if (l < min || l > max) {
            throw new IllegalArgumentException(key + " out of range [" + min + ", " + max + "]");
        }
        return (int) (long) l;
    }

    private static long reqId(Map<String, Object> m) {
        return intIn(m, "req_id", 0, Integer.MAX_VALUE);
    }

    /** Validates a decimal string (the wire carries money and quantities as strings). */
    private static void decimal(Map<String, Object> m, String key, boolean required, boolean positive) {
        Object v = m.get(key);
        if (v == null) {
            if (required) {
                throw new IllegalArgumentException(key + " is required");
            }
            return;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(key + " must be a decimal string");
        }
        BigDecimal b;
        try {
            b = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a decimal: " + s);
        }
        if (positive && b.signum() <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero");
        }
        if (!positive && b.signum() < 0) {
            throw new IllegalArgumentException(key + " must not be negative");
        }
    }
}
