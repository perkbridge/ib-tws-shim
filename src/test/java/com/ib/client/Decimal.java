// SPDX-License-Identifier: GPL-3.0-or-later
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

import java.math.BigDecimal;

public final class Decimal {
    private final BigDecimal v;
    private Decimal(BigDecimal v) { this.v = v; }
    public static Decimal parse(String s) { return new Decimal(new BigDecimal(s)); }
    @Override public String toString() { return v.toPlainString(); }
}
