// SPDX-License-Identifier: GPL-3.0-only
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

public class Execution {
    public String execId = "0001.01", time = "20261118  09:30:01 US/Eastern", acctNumber = "DU1", exchange = "SMART", side = "BOT";
    public Decimal shares = Decimal.parse("10"); public double price = 187.25; public int permId = 99, clientId = 7, orderId = 5;
    public String execId() { return execId; } public String time() { return time; } public String acctNumber() { return acctNumber; }
    public String exchange() { return exchange; } public String side() { return side; } public Decimal shares() { return shares; }
    public double price() { return price; } public int permId() { return permId; } public int clientId() { return clientId; }
    public int orderId() { return orderId; }
}
