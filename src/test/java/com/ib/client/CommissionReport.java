// SPDX-License-Identifier: GPL-3.0-or-later
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

public class CommissionReport {
    public String execId = "0001.01", currency = "USD"; public double commission = 1.0, realizedPNL = Double.MAX_VALUE;
    public String execId() { return execId; } public double commission() { return commission; } public String currency() { return currency; }
    public double realizedPNL() { return realizedPNL; }
}
