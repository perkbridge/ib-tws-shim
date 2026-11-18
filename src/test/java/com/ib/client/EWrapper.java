// SPDX-License-Identifier: GPL-3.0-or-later
// TEST DOUBLE. Not IB code: a minimal stand-in written for this repository so the
// adapter can be exercised without IB's library. Names mirror the public API shape.
package com.ib.client;

public interface EWrapper {
    void connectAck();
    void nextValidId(int orderId);
    void managedAccounts(String accountsList);
    void currentTime(long time);
    void connectionClosed();
    void error(Exception e);
    void error(String s);
    void error(int id, int errorCode, String errorMsg, String advancedOrderRejectJson);
    void openOrder(int orderId, Contract contract, Order order, OrderState orderState);
    void openOrderEnd();
    void orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice, long permId,
        int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice);
    void execDetails(int reqId, Contract contract, Execution execution);
    void execDetailsEnd(int reqId);
    void commissionReport(CommissionReport report);
    void position(String account, Contract contract, Decimal pos, double avgCost);
    void positionEnd();
    void tickPrice(int tickerId, int field, double price, TickAttrib attribs);
    void tickSize(int tickerId, int field, Decimal size);
    void displayGroupList(int reqId, String groups); // a callback the adapter does not forward
}
