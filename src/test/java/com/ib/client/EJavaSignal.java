// SPDX-License-Identifier: GPL-3.0-only
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class EJavaSignal {
    private final Semaphore s = new Semaphore(0);
    public void issueSignal() { s.release(); }
    public void waitForSignal() { try { s.tryAcquire(200, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
