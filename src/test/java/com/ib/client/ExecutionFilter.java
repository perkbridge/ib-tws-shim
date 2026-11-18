// SPDX-License-Identifier: GPL-3.0-only
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

public class ExecutionFilter {
    public int clientId; public String acctCode, time, symbol, secType, exchange, side;
    public void clientId(int v) { clientId = v; } public void acctCode(String v) { acctCode = v; } public void time(String v) { time = v; }
    public void symbol(String v) { symbol = v; } public void secType(String v) { secType = v; }
    public void exchange(String v) { exchange = v; } public void side(String v) { side = v; }
}
