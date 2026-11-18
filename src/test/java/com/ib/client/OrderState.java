// SPDX-License-Identifier: GPL-3.0-only
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

public class OrderState {
    public String status = "Submitted"; public double commission = Double.MAX_VALUE;
    public String commissionCurrency = "", warningText = "", initMarginChange = "1.7976931348623157E308";
    public String status() { return status; } public double commission() { return commission; }
    public String commissionCurrency() { return commissionCurrency; } public String warningText() { return warningText; }
    public String initMarginChange() { return initMarginChange; }
}
