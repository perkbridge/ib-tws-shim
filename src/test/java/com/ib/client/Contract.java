// SPDX-License-Identifier: GPL-3.0-only
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

public class Contract {
    private int conid; private String symbol, secType, exchange, primaryExch, currency, localSymbol,
        tradingClass, lastTradeDateOrContractMonth, right, multiplier; private double strike;
    public int conid() { return conid; } public void conid(int v) { conid = v; }
    public String symbol() { return symbol; } public void symbol(String v) { symbol = v; }
    public String getSecType() { return secType; } public void secType(String v) { secType = v; }
    public String exchange() { return exchange; } public void exchange(String v) { exchange = v; }
    public String primaryExch() { return primaryExch; } public void primaryExch(String v) { primaryExch = v; }
    public String currency() { return currency; } public void currency(String v) { currency = v; }
    public String localSymbol() { return localSymbol; } public void localSymbol(String v) { localSymbol = v; }
    public String tradingClass() { return tradingClass; } public void tradingClass(String v) { tradingClass = v; }
    public String lastTradeDateOrContractMonth() { return lastTradeDateOrContractMonth; }
    public void lastTradeDateOrContractMonth(String v) { lastTradeDateOrContractMonth = v; }
    public double strike() { return strike; } public void strike(double v) { strike = v; }
    public String getRight() { return right; } public void right(String v) { right = v; }
    public String multiplier() { return multiplier; } public void multiplier(String v) { multiplier = v; }
}
