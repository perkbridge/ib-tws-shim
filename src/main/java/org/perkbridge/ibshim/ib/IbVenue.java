// SPDX-License-Identifier: GPL-3.0-only
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
    private volatile java.net.Socket pendingSocket;
    private volatile java.net.Socket liveSocket;
    private volatile boolean detachRequested;

    private final Map<String, AtomicLong> dropped = new ConcurrentHashMap<>();

    /** Per-connection state touched by callbacks. */
    private static final class Conn {
        final Object lock = new Object(); // orders "connected" against the gateway closing
        final CountDownLatch nextId = new CountDownLatch(1);
        final CountDownLatch accounts = new CountDownLatch(1);
        volatile long nextValidId = -1;
        volatile List<Object> accountList = List.of();
        volatile int lastErrorCode;
        volatile String lastErrorMessage = "";
        volatile boolean userDisconnect;
        volatile boolean disconnectedSent;
        volatile boolean dead; // torn down: its late callbacks must not reach any client
        volatile boolean aborted; // the client went away while connect was waiting
        volatile boolean connectedSent;
        volatile boolean closedEarly; // the gateway closed before the handshake finished
        volatile int firstErrorCode;
        volatile String firstErrorMessage = "";
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
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField("CLIENT_VERSION");
                f.setAccessible(true);
                Object cv = f.get(null);
                if (cv != null) {
                    return "client_version=" + cv;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // look one level up
            }
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
        this.detachRequested = false;
        this.sink = s;
    }

    @Override
    public void detach() {
        // a connect that is still waiting holds the monitor: wake it first
        detachRequested = true; // a connect that has not published its socket yet sees this
        for (java.net.Socket ps : new java.net.Socket[] {pendingSocket, liveSocket}) {
            if (ps != null) {
                try {
                    ps.close(); // frees a call that is blocked in socket I/O and holds the monitor
                } catch (java.io.IOException ignored) {
                    // already closed
                }
            }
        }
        Conn pending = conn;
        if (pending != null) {
            pending.aborted = true;
            pending.nextId.countDown();
            pending.accounts.countDown();
        }
        synchronized (this) {
            Sink s = sink;
            sink = null;
            teardown(false);
            logDropped("detach");
            if (s != null) {
                log.debug("venue_detached");
            }
        }
    }

    private void emitC(Conn c, String name, Map<String, Object> data) {
        Sink sk = sink;
        if (sk != null && c != null && !c.dead) {
            sk.event(name, data);
        }
    }

    private long emitSeqC(Conn c, String name, Map<String, Object> data) {
        Sink sk = sink;
        return sk == null || c == null || c.dead ? 0 : sk.event(name, data);
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
        } catch (LinkageError e) {
            log.error("api_incomplete", "op", op, "error", e.toString());
            throw new OpException(OpException.VENUE_ERROR, "the IB client library is incomplete: " + e.getMessage());
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
                conn0().lastPositionSeq = 0;
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
        if (c == null || client == null || !connected || !Boolean.TRUE.equals(safeIsConnected(client))) {
            // IB's own isConnected is checked too: its placeOrder answers a closed socket with an
            // error event (504, no order id) and returns normally, which must not look like success
            throw new OpException(OpException.NOT_CONNECTED, "not connected to the gateway");
        }
        return c;
    }

    private Object call(String method, Object... args) {
        return Reflect.call(client, method, args);
    }

    /**
     * A call that may already have reached the gateway when it fails: its failures are
     * {@code venue_error} (ambiguous for the client, which reconciles), never {@code bad_request}.
     * A call the library does not offer at all was certainly not made and keeps its own error.
     */
    private Object callAmbiguous(String method, Object... args) throws OpException {
        try {
            return Reflect.call(client, method, args);
        } catch (Reflect.MissingMemberException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            log.error("ib_call_failed", "call", method, "error", e.toString());
            throw new OpException(OpException.VENUE_ERROR, method + " failed inside the IB client; the outcome is unknown");
        }
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
        // The socket is opened here, with a connect timeout and a read timeout for IB's blocking
        // handshake, so that a silent host cannot hold the client slot; detach() can close it.
        java.net.Socket sock = new java.net.Socket();
        pendingSocket = sock;
        liveSocket = sock;
        try {
            if (detachRequested) {
                throw new java.io.IOException("client gone");
            }
            sock.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(timeoutMs);
            if (detachRequested) {
                throw new java.io.IOException("client gone");
            }
            Reflect.call(client, "eConnect", sock, (long) clientId);
            sock.setSoTimeout(0);
        } catch (java.io.IOException | RuntimeException e) {
            boolean gone = c.aborted || detachRequested;
            try {
                sock.close();
            } catch (java.io.IOException ignored) {
                // closing after failure
            }
            teardown(false);
            if (gone) {
                throw new OpException(OpException.NOT_CONNECTED, "the client went away while connecting");
            }
            throw new OpException(OpException.VENUE_ERROR, "could not connect to " + host + ":" + port + ": " + e.getMessage(), 502);
        } finally {
            pendingSocket = null;
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
            boolean got = c.nextId.await(Math.max(1, remainingMs(deadline)), TimeUnit.MILLISECONDS);
            if (c.aborted) {
                teardown(false);
                throw new OpException(OpException.NOT_CONNECTED, "the client went away while connecting");
            }
            if (!got) {
                int code = c.firstErrorCode;
                String msg = c.firstErrorMessage;
                teardown(false);
                throw new OpException(OpException.TIMEOUT,
                        "the gateway accepted the socket but sent no next valid id within " + timeoutMs + " ms"
                                + (msg.isEmpty() ? "" : " (" + msg + ")"), code);
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
        if (ctNorm != null) {
            r.put("connection_time", ctNorm);
        } else {
            r.put("connection_time_raw", ct); // the normalised field is omitted when the zone is unknown
        }
        r.put("next_valid_id", c.nextValidId);
        r.put("accounts", new ArrayList<>(c.accountList));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("server_version", r.get("server_version"));
        if (ctNorm != null) {
            ev.put("connection_time", ctNorm);
        } else {
            ev.put("connection_time_raw", ct);
        }
        ev.put("client_id", (long) clientId);
        boolean closed;
        synchronized (c.lock) {
            closed = c.closedEarly;
            if (!closed) {
                c.connectedSent = true;
                emitC(c, "connected", ev);
            }
        }
        if (closed) {
            int code = c.firstErrorCode;
            String msg = c.firstErrorMessage;
            teardown(false);
            throw new OpException(OpException.VENUE_ERROR,
                    "the gateway closed the connection during the handshake" + (msg.isEmpty() ? "" : ": " + msg), code);
        }
        log.info("gateway_connected", "host", host, "port", port, "client_id", clientId);
        return r;
    }

    private static long remainingMs(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    private void readerLoop(Object cl0, Object sig0, Object rd0, Conn c) {
        String failure = null;
        try {
            while (Boolean.TRUE.equals(safeIsConnected(cl0))) {
                Reflect.call(sig0, "waitForSignal");
                try {
                    Reflect.call(rd0, "processMsgs");
                } catch (RuntimeException e) {
                    // IB lost a message it could not decode: a hole in the stream. End the session so
                    // the client reconnects and reconciles instead of trusting what follows.
                    failure = "decode failure: " + e;
                    log.error("ib_decode_failed", "error", String.valueOf(e));
                    Sink sk = sink;
                    if (sk != null && !c.dead) {
                        sk.abort("ib_decode_failed");
                    }
                    break;
                }
            }
            if (failure == null && !c.dead) {
                try {
                    Reflect.call(rd0, "processMsgs"); // messages queued before the socket closed
                } catch (RuntimeException ignored) {
                    // nothing left to read
                }
            }
        } catch (Throwable t) { // a LinkageError must not end this thread silently
            if (!(t instanceof RuntimeException re && re.getCause() instanceof InterruptedException)) {
                failure = t.toString();
                log.error("ib_reader_failed", "error", failure);
            }
        }
        if (failure != null) {
            try {
                Reflect.call(cl0, "eDisconnect"); // never leave a gateway session nobody reads
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
        if (c.userDisconnect || c.dead || conn != c) {
            return;
        }
        synchronized (c.lock) {
            if (!c.connectedSent) {
                // the gateway closed during the handshake: wake connect, which answers with an error
                c.closedEarly = true;
                c.nextId.countDown();
                c.accounts.countDown();
            } else if (failure != null) {
                connectionClosed(c, "error", failure);
            } else {
                connectionClosed(c, "gateway_closed", "gateway socket closed");
            }
        }
    }

    /** Disconnects from the gateway and drops per-connection state. */
    private void teardown(boolean emitRequested) {
        Object cl0 = client;
        Conn conn0 = conn;
        if (cl0 != null) {
            try {
                Reflect.call(cl0, "eDisconnect");
            } catch (RuntimeException e) {
                log.debug("disconnect_failed", "error", e.toString());
            }
        }
        if (conn0 != null) {
            conn0.dead = true; // late callbacks of this connection go nowhere
        }
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
            if (t != Thread.currentThread()) {
                try {
                    t.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // announce a requested disconnect last, so no event of this connection can follow it
        if (emitRequested && conn0 != null && !conn0.disconnectedSent) {
            conn0.disconnectedSent = true;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("reason", "requested");
            Sink sk = sink;
            if (sk != null) {
                sk.event("disconnected", d);
            }
        }
        client = null;
        signal = null;
        reader = null;
        readerThread = null;
        liveSocket = null;
        connected = false;
        if (!emitRequested) {
            conn = null;
        }
    }

    private void connectionClosed(Conn c, String reason, String message) {
        synchronized (c.lock) {
            if (!c.connectedSent) {
                c.closedEarly = true; // never report a disconnect for a connection that was not announced
                c.nextId.countDown();
                c.accounts.countDown();
                return;
            }
        }
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
        emitC(c, "disconnected", d);
        logDropped("disconnected");
    }

    // ---------------------------------------------------------------- orders

    private Map<String, Object> placeOrder(Map<String, Object> a) throws OpException {
        requireConn();
        strict(a, Set.of("order_id", "contract", "order"), "place_order args");
        noNulls(a, "place_order");
        int orderId = intIn(a, "order_id", 1, Integer.MAX_VALUE);
        Map<String, Object> o = obj(a, "order", true);
        strict(o, ORDER_KEYS, "order");
        noNulls(o, "order");
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
        checkOrderTypes(o);
        decimal(o, "total_quantity", true, true);
        if ((type.equals("LMT") || type.equals("STP LMT")) && !o.containsKey("lmt_price")) {
            throw new IllegalArgumentException("order.lmt_price is required for " + type);
        }
        if ((type.equals("STP") || type.equals("STP LMT")) && !o.containsKey("aux_price")) {
            throw new IllegalArgumentException("order.aux_price (the stop price) is required for " + type);
        }
        decimalAny(o, "lmt_price"); // spreads and some futures trade at negative prices
        decimalAny(o, "aux_price");
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
        callAmbiguous("placeOrder", (long) orderId, contract, order);
        return empty();
    }

    private Map<String, Object> cancelOrder(Map<String, Object> a) throws OpException {
        requireConn();
        strict(a, Set.of("order_id", "manual_time"), "cancel_order args");
        noNulls(a, "cancel_order");
        int id = intIn(a, "order_id", 1, Integer.MAX_VALUE);
        String manual = str(a, "manual_time", false);
        String ib = manual == null ? "" : Times.toIb(manual);
        try {
            callAmbiguous("cancelOrder", (long) id, ib);
        } catch (Reflect.MissingMemberException e) {
            Object oc = newOf("com.ib.client.OrderCancel");
            if (manual != null) {
                Reflect.set(oc, "manualOrderCancelTime", ib);
            }
            callAmbiguous("cancelOrder", (long) id, oc);
        }
        return empty();
    }

    private Object contract(Map<String, Object> c, boolean strictKeys) {
        if (strictKeys) {
            strict(c, CONTRACT_KEYS, "contract");
            noNulls(c, "contract");
        }
        decimalAny(c, "strike");
        Object k = newOf("com.ib.client.Contract");
        Mapper.in(c, Mapper.CONTRACT, k);
        return k;
    }

    private Object execFilter(Map<String, Object> f) {
        Object ef = newOf("com.ib.client.ExecutionFilter");
        if (f == null) {
            return ef;
        }
        Map<String, Object> g = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : f.entrySet()) {
            if (e.getValue() != null) {
                g.put(e.getKey(), e.getValue());
            }
        }
        for (String k : new String[] {"account", "time", "symbol", "sec_type", "exchange", "side"}) {
            if (g.containsKey(k) && !(g.get(k) instanceof String)) {
                throw new IllegalArgumentException("filter." + k + " must be a string");
            }
        }
        if (g.containsKey("client_id")) {
            intIn(g, "client_id", 0, Integer.MAX_VALUE);
        }
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
            } catch (RuntimeException | LinkageError e) {
                // an event we cannot translate would be lost silently: end the client session so
                // the client reconnects and reconciles instead of trusting a stream with a hole
                log.error("callback_failed", "callback", method.getName(), "error", e.toString());
                Sink sk = sink;
                if (sk != null && !c.dead) {
                    sk.abort("callback_failed:" + method.getName());
                }
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
                emitC(c, "next_valid_id", d);
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
                emitC(c, "managed_accounts", d);
            }
            case "currentTime" -> {
                d.put("time", Times.epochToRfc3339(num(ar[0])));
                d.put("epoch", num(ar[0]));
                emitC(c, "current_time", d);
            }
            case "connectionClosed" -> {
                // IB calls this from its own reader thread while decoded messages may still wait for
                // ours: never announce the close here. Wake the shim reader, which announces it after
                // draining everything that was queued before the socket closed.
                if (!c.connectedSent) {
                    connectionClosed(c, "gateway_closed", "gateway socket closed"); // wakes connect
                } else if (!c.userDisconnect) {
                    Object sig = signal;
                    if (sig != null) {
                        try {
                            Reflect.call(sig, "issueSignal");
                        } catch (RuntimeException ignored) {
                            // reader is already stopping
                        }
                    }
                }
            }
            case "error" -> onError(c, ar);
            case "openOrder" -> {
                d.put("order_id", num(ar[0]));
                d.put("contract", Mapper.out(ar[1], Mapper.CONTRACT));
                d.put("order", Mapper.out(ar[2], Mapper.ORDER_OUT));
                d.put("order_state", Mapper.out(ar[3], Mapper.ORDER_STATE));
                emitC(c, "open_order", d);
            }
            case "openOrderEnd" -> emitC(c, "open_order_end", d);
            case "completedOrder" -> {
                d.put("contract", Mapper.out(ar[0], Mapper.CONTRACT));
                d.put("order", Mapper.out(ar[1], Mapper.ORDER_OUT));
                d.put("order_state", Mapper.out(ar[2], Mapper.ORDER_STATE));
                emitC(c, "completed_order", d);
            }
            case "completedOrdersEnd" -> emitC(c, "completed_orders_end", d);
            case "orderStatus" -> {
                d.put("order_id", num(ar[0]));
                d.put("status", s(ar[1]));
                putDec(d, "filled", ar[2]);
                putDec(d, "remaining", ar[3]);
                putDec(d, "avg_fill_price", ar[4]);
                d.put("perm_id", num(ar[5]));
                d.put("parent_id", num(ar[6]));
                putDec(d, "last_fill_price", ar[7]);
                d.put("client_id", num(ar[8]));
                if (!s(ar[9]).isEmpty()) {
                    d.put("why_held", s(ar[9]));
                }
                putDec(d, "mkt_cap_price", ar[10]);
                emitC(c, "order_status", d);
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
                emitC(c, "exec_details", d);
            }
            case "execDetailsEnd" -> {
                d.put("req_id", num(ar[0]));
                emitC(c, "exec_details_end", d);
            }
            case "commissionReport", "commissionAndFeesReport" -> {
                emitC(c, "commission_report", Mapper.out(ar[0], Mapper.COMMISSION));
            }
            case "position" -> {
                d.put("account", s(ar[0]));
                d.put("contract", Mapper.out(ar[1], Mapper.CONTRACT));
                putDec(d, "position", ar[2]);
                putDec(d, "avg_cost", ar[3]);
                d.put("snapshot", c.positionsSnapshot);
                long sq = emitSeqC(c, "position", d);
                if (c.positionsSnapshot) {
                    c.lastPositionSeq = sq;
                }
            }
            case "positionEnd" -> {
                Sink sk = sink;
                d.put("as_of_seq", c.lastPositionSeq != 0 ? c.lastPositionSeq : (sk == null ? 0L : sk.lastSeq()));
                c.positionsSnapshot = false;
                emitC(c, "position_end", d);
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
                long sq = emitSeqC(c, "account_summary", d);
                if (snap) {
                    st[1] = sq;
                }
            }
            case "accountSummaryEnd" -> {
                long id = num(ar[0]);
                long[] st = c.accountSummary.get(id);
                d.put("req_id", id);
                Sink sk = sink;
                d.put("as_of_seq", st != null && st[1] != 0 ? st[1] : (sk == null ? 0L : sk.lastSeq()));
                if (st != null) {
                    st[0] = 0;
                }
                emitC(c, "account_summary_end", d);
            }
            case "updateAccountValue" -> {
                d.put("key", s(ar[0]));
                d.put("value", s(ar[1]));
                d.put("currency", s(ar[2]));
                d.put("account", s(ar[3]));
                emitC(c, "account_value", d);
            }
            case "updatePortfolio" -> {
                d.put("contract", Mapper.out(ar[0], Mapper.CONTRACT));
                putDec(d, "position", ar[1]);
                putDec(d, "market_price", ar[2]);
                putDec(d, "market_value", ar[3]);
                putDec(d, "average_cost", ar[4]);
                putDec(d, "unrealized_pnl", ar[5]);
                putDec(d, "realized_pnl", ar[6]);
                d.put("account", s(ar[7]));
                emitC(c, "portfolio", d);
            }
            case "updateAccountTime" -> {
                d.put("time_raw", s(ar[0]));
                emitC(c, "account_time", d);
            }
            case "accountDownloadEnd" -> {
                d.put("account", s(ar[0]));
                emitC(c, "account_download_end", d);
            }
            case "contractDetails" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("req_id", num(ar[0]));
                m.put("contract", Mapper.out(Reflect.get(ar[1], "contract"), Mapper.CONTRACT));
                m.putAll(Mapper.out(ar[1], Mapper.CONTRACT_DETAILS));
                emitC(c, "contract_details", m);
            }
            case "contractDetailsEnd" -> {
                d.put("req_id", num(ar[0]));
                emitC(c, "contract_details_end", d);
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
                emitC(c, "market_rule", d);
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
                emitC(c, "historical_bar", d);
            }
            case "historicalDataEnd" -> {
                d.put("req_id", num(ar[0]));
                d.put("start_raw", s(ar[1]));
                d.put("end_raw", s(ar[2]));
                emitC(c, "historical_data_end", d);
            }
            case "pnlSingle" -> {
                d.put("req_id", num(ar[0]));
                putDec(d, "position", ar[1]);
                putDec(d, "daily_pnl", ar[2]);
                putDec(d, "unrealized_pnl", ar[3]);
                putDec(d, "realized_pnl", ar[4]);
                putDec(d, "value", ar[5]);
                emitC(c, "pnl_single", d);
            }
            case "tickPrice" -> {
                if (Mapper.unset(ar[2])) {
                    break; // IB's "no price" marker is not a price
                }
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
                emitC(c, "tick_price", d);
            }
            case "tickSize" -> {
                d.put("req_id", num(ar[0]));
                d.put("tick_type", num(ar[1]));
                putDec(d, "size", ar[2]);
                emitC(c, "tick_size", d);
            }
            case "tickString" -> {
                d.put("req_id", num(ar[0]));
                d.put("tick_type", num(ar[1]));
                d.put("value", s(ar[2]));
                emitC(c, "tick_string", d);
            }
            case "tickGeneric" -> {
                d.put("req_id", num(ar[0]));
                d.put("tick_type", num(ar[1]));
                d.put("value", Mapper.decimalString(ar[2]));
                emitC(c, "tick_generic", d);
            }
            case "tickSnapshotEnd" -> {
                d.put("req_id", num(ar[0]));
                emitC(c, "tick_snapshot_end", d);
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
        if (c.firstErrorCode == 0 && code > 0 && severity(code).equals("error")) {
            c.firstErrorCode = code;
            c.firstErrorMessage = msg;
        }
        d.put("req_id", id);
        d.put("order_id", id);
        d.put("code", (long) code);
        d.put("message", msg);
        d.put("severity", severity(code));
        if (!adv.isEmpty()) {
            d.put("advanced_order_reject", adv);
        }
        emitC(c, "error", d);
        String state = switch (code) {
            case 1100 -> "lost";
            case 1101 -> "restored_orders_lost";
            case 1102 -> "restored_orders_kept";
            default -> null;
        };
        if (state != null) {
            Map<String, Object> cd = new LinkedHashMap<>();
            cd.put("state", state);
            emitC(c, "connectivity", cd);
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

    private static void noNulls(Map<String, Object> m, String what) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getValue() == null) {
                throw new IllegalArgumentException(what + "." + e.getKey() + " must not be null; omit the field to use IB's default");
            }
        }
    }

    private static void checkOrderTypes(Map<String, Object> o) {
        for (String k : new String[] {"action", "order_type", "tif", "order_ref", "account", "oca_group", "good_till_date"}) {
            if (o.get(k) != null && !(o.get(k) instanceof String)) {
                throw new IllegalArgumentException("order." + k + " must be a string");
            }
        }
        for (String k : new String[] {"transmit", "outside_rth", "what_if"}) {
            if (o.get(k) != null && !(o.get(k) instanceof Boolean)) {
                throw new IllegalArgumentException("order." + k + " must be a boolean");
            }
        }
        if (o.get("parent_id") != null) {
            intIn(o, "parent_id", 0, Integer.MAX_VALUE);
        }
        for (String k : new String[] {"total_quantity", "lmt_price", "aux_price"}) {
            if (o.get(k) != null && !(o.get(k) instanceof String)) {
                throw new IllegalArgumentException("order." + k + " must be a decimal string");
            }
        }
    }

    /** A decimal string of either sign. */
    private static void decimalAny(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
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
        if (Double.isInfinite(b.doubleValue())) {
            throw new IllegalArgumentException(key + " is out of range");
        }
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
        if (Double.isInfinite(b.doubleValue())) {
            throw new IllegalArgumentException(key + " is out of range");
        }
        if (positive && b.signum() <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero");
        }
        if (!positive && b.signum() < 0) {
            throw new IllegalArgumentException(key + " must not be negative");
        }
    }
}
