// SPDX-License-Identifier: GPL-3.0-only
package org.perkbridge.ibshim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Venue double for core tests. */
final class FakeVenue implements Venue {
    volatile Sink sink;
    final AtomicInteger attached = new AtomicInteger();
    final AtomicInteger detached = new AtomicInteger();
    volatile CountDownLatch detachLatch = new CountDownLatch(1);
    volatile boolean connected;

    @Override public String apiClientVersion() { return "fake-api/1"; }
    @Override public List<String> ops() { return List.of("echo", "fail"); }
    @Override public boolean connected() { return connected; }

    @Override
    public void attach(Sink s) {
        sink = s;
        attached.incrementAndGet();
    }

    @Override
    public Map<String, Object> handle(String op, Map<String, Object> args) throws OpException {
        if (op.equals("fail")) {
            throw new OpException(OpException.VENUE_ERROR, "boom", 201);
        }
        return new LinkedHashMap<>(args);
    }

    @Override
    public void detach() {
        sink = null;
        detached.incrementAndGet();
        detachLatch.countDown();
    }
}
