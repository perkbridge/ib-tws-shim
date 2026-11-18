// SPDX-License-Identifier: GPL-3.0-only
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

public class EReader {
    private final EClientSocket c;
    public EReader(EClientSocket client, EJavaSignal signal) { c = client; }
    public void start() { /* the real reader starts a thread; the double needs none */ }
    public void processMsgs() { c.drain(); }
}
