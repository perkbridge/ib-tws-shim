// SPDX-License-Identifier: GPL-3.0-only
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository.
package com.ib.client;

public enum OrderType {
    None(""), STP("STP"), TRAIL("TRAIL");
    private final String api;
    OrderType(String api) { this.api = api; }
    public String getApiString() { return api; }
}
