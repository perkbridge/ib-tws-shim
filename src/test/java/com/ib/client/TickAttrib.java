// SPDX-License-Identifier: GPL-3.0-or-later
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

public class TickAttrib {
    public boolean canAutoExecute = true, pastLimit, preOpen;
    public boolean canAutoExecute() { return canAutoExecute; } public boolean pastLimit() { return pastLimit; } public boolean preOpen() { return preOpen; }
}
