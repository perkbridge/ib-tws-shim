// SPDX-License-Identifier: GPL-3.0-only
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.ArrayList;
import java.util.List;

public class EClientSocket {
    public static final int CLIENT_VERSION = 66;
    /** Test hooks. */
    public static volatile EClientSocket last;
    public static final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
    public static volatile Contract lastContract;
    public static volatile Order lastOrder;
    public static volatile ExecutionFilter lastFilter;
    public static volatile String execTime;
    public final EWrapper wrapper;
    private final EJavaSignal signal;
    private final ConcurrentLinkedQueue<Runnable> q = new ConcurrentLinkedQueue<>();
    private volatile boolean connected;

    public EClientSocket(EWrapper w, EJavaSignal s) { wrapper = w; signal = s; last = this; }

    /** Test hook: what the next connect does: "", "refuse", "silent", "dropme" or "hang". */
    public static volatile String mode = "";

    public void eConnect(java.net.Socket socket, int clientId) {
        String m = mode;
        if (m.equals("hang")) { // like IB's handshake: a blocking read on the socket
            try { socket.getInputStream().read(); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        String host = m.isEmpty() ? "127.0.0.1" : m.equals("refuse") ? "refuse" : m.equals("silent") ? "silent" : m.equals("dropme") ? "dropme" : "127.0.0.1";
        calls.add("eConnect " + socket.getInetAddress().getHostAddress() + " " + socket.getPort() + " " + clientId);
        eConnect(host, socket.getPort(), clientId);
    }

    public void eConnect(String host, int port, int clientId) {
        if (host.equals("refuse")) { wrapper.error(-1, 502, "Couldn't connect to TWS", ""); return; }
        connected = true;
        if (host.equals("silent")) { return; }
        if (host.equals("dropme")) {
            post(() -> wrapper.error(-1, 326, "Unable to connect as the client id is already in use", ""));
            post(() -> { connected = false; wrapper.connectionClosed(); });
            return;
        }
        post(() -> wrapper.nextValidId(1000));
        post(() -> wrapper.managedAccounts("DU1,DU2"));
    }
    public void eDisconnect() {
        calls.add("eDisconnect");
        boolean was = connected;
        connected = false;
        signal.issueSignal();
        if (was) { wrapper.connectionClosed(); } // like the real library: on the calling thread
    }
    /** Test hook: IB's own reader thread sees the socket close while decoded messages still wait. */
    public void ibThreadClose() { wrapper.error(new RuntimeException("EOF")); eDisconnect(); }
    public boolean isConnected() { return connected; }
    public int serverVersion() { return 176; }
    public String getTwsConnectionTime() { return "20261118 14:00:00 UTC"; }

    /** Test hook: run a callback on the reader thread. */
    public void post(Runnable r) { q.add(r); signal.issueSignal(); }
    public void drain() { Runnable r; while ((r = q.poll()) != null) r.run(); }
    /** Test hook: the gateway drops the socket. */
    public void gatewayClosed() { connected = false; post(wrapper::connectionClosed); }

    public void reqIds(int n) { calls.add("reqIds " + n); post(() -> wrapper.nextValidId(2000)); }
    public void reqCurrentTime() { calls.add("reqCurrentTime"); post(() -> wrapper.currentTime(1795000000L)); }
    public void placeOrder(int id, Contract c, Order o) {
        calls.add("placeOrder " + id); lastContract = c; lastOrder = o;
        post(() -> wrapper.openOrder(id, c, o, new OrderState()));
        post(() -> wrapper.orderStatus(id, "Submitted", Decimal.parse("0"), Decimal.parse("10"), 0.0, 4242L, 0, 0.0, 7, "", Double.MAX_VALUE));
    }
    public void cancelOrder(int id, String manual) { calls.add("cancelOrder " + id + " [" + manual + "]"); }
    public void reqGlobalCancel() { calls.add("reqGlobalCancel"); }
    public void reqAllOpenOrders() { calls.add("reqAllOpenOrders"); post(wrapper::openOrderEnd); }
    public void reqOpenOrders() { calls.add("reqOpenOrders"); post(wrapper::openOrderEnd); }
    public void reqExecutions(int id, ExecutionFilter f) {
        calls.add("reqExecutions " + id); lastFilter = f;
        Contract c = new Contract(); c.symbol("AAPL"); c.conid(265598);
        Execution ex = new Execution(); if (execTime != null) { ex.time = execTime; }
        post(() -> wrapper.execDetails(id, c, ex));
        post(() -> wrapper.commissionReport(new CommissionReport()));
        post(() -> wrapper.execDetailsEnd(id));
    }
    public static volatile boolean emptyPositions;
    public void reqPositions() {
        calls.add("reqPositions");
        Contract c = new Contract(); c.symbol("AAPL"); c.conid(265598);
        if (!emptyPositions) { post(() -> wrapper.position("DU1", c, Decimal.parse("10"), 187.25)); }
        post(wrapper::positionEnd);
    }
    public void cancelPositions() { calls.add("cancelPositions"); }
    public void reqMktData(int id, Contract c, String ticks, boolean snap, boolean reg, List<Object> opts) {
        calls.add("reqMktData " + id + " snap=" + snap);
        for (int i = 0; i < 5; i++) { final int n = i; post(() -> wrapper.tickPrice(id, 1, 100 + n, new TickAttrib())); }
    }
    public void cancelMktData(int id) { calls.add("cancelMktData " + id); }
    /** Test hook: raises an IB error callback. */
    public void raise(int id, int code, String msg) { post(() -> wrapper.error(id, code, msg, "")); }
}
