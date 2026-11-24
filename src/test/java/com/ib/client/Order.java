// SPDX-License-Identifier: GPL-3.0-only
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

public class Order {
    public enum Action { BUY, SELL; public String getApiString() { return name(); } }
    private Action action; private String orderType, tif, orderRef, account, ocaGroup, goodTillDate;
    private Decimal totalQuantity; private double lmtPrice = Double.MAX_VALUE, auxPrice = Double.MAX_VALUE;
    private int ocaType, parentId; private boolean transmit = true, outsideRth, whatIf;
    public String getAction() { return action == null ? null : action.getApiString(); }
    public void action(Action v) { action = v; }
    public String getOrderType() { return orderType; } public void orderType(String v) { orderType = v; }
    public Decimal totalQuantity() { return totalQuantity; } public void totalQuantity(Decimal v) { totalQuantity = v; }
    public double lmtPrice() { return lmtPrice; } public void lmtPrice(double v) { lmtPrice = v; }
    public double auxPrice() { return auxPrice; } public void auxPrice(double v) { auxPrice = v; }
    public String getTif() { return tif; } public void tif(String v) { tif = v; }
    public String orderRef() { return orderRef; } public void orderRef(String v) { orderRef = v; }
    public String account() { return account; } public void account(String v) { account = v; }
    public String ocaGroup() { return ocaGroup; } public void ocaGroup(String v) { ocaGroup = v; }
    public int getOcaType() { return ocaType; } public void ocaType(int v) { ocaType = v; }
    public int parentId() { return parentId; } public void parentId(int v) { parentId = v; }
    public boolean transmit() { return transmit; } public void transmit(boolean v) { transmit = v; }
    public boolean outsideRth() { return outsideRth; } public void outsideRth(boolean v) { outsideRth = v; }
    public String goodTillDate() { return goodTillDate; } public void goodTillDate(String v) { goodTillDate = v; }
    public boolean whatIf() { return whatIf; } public void whatIf(boolean v) { whatIf = v; }
    private int permId; private double trailStopPrice = Double.MAX_VALUE, trailingPercent = Double.MAX_VALUE;
    public int permId() { return permId; } public void permId(int v) { permId = v; }
    public double trailStopPrice() { return trailStopPrice; } public void trailStopPrice(double v) { trailStopPrice = v; }
    public double trailingPercent() { return trailingPercent; } public void trailingPercent(double v) { trailingPercent = v; }
    // native protections (names as IB's Order; a double, NOT the real library)
    private int triggerMethod; private String goodAfterTime, adjustedOrderType;
    private double triggerPrice = Double.MAX_VALUE, adjustedStopPrice = Double.MAX_VALUE, adjustedTrailingAmount = Double.MAX_VALUE;
    public int triggerMethod() { return triggerMethod; } public void triggerMethod(int v) { triggerMethod = v; }
    public String goodAfterTime() { return goodAfterTime; } public void goodAfterTime(String v) { goodAfterTime = v; }
    public double triggerPrice() { return triggerPrice; } public void triggerPrice(double v) { triggerPrice = v; }
    public String getAdjustedOrderType() { return adjustedOrderType; } public void adjustedOrderType(String v) { adjustedOrderType = v; }
    public double adjustedStopPrice() { return adjustedStopPrice; } public void adjustedStopPrice(double v) { adjustedStopPrice = v; }
    public double adjustedTrailingAmount() { return adjustedTrailingAmount; } public void adjustedTrailingAmount(double v) { adjustedTrailingAmount = v; }
}
