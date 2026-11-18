// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim.ib;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Table-driven translation between the protocol's JSON objects and IB's
 * bean-style classes. Each row names the JSON key and the IB property (with
 * aliases for names that changed between library versions).
 */
final class Mapper {
    private Mapper() {}

    enum Kind { STR, INT, DEC, BOOL }

    record Row(String json, Kind kind, String... props) {}

    static Row r(String json, Kind kind, String... props) {
        return new Row(json, kind, props.length == 0 ? new String[] {camel(json)} : props);
    }

    static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    static final Row[] CONTRACT = {
        r("con_id", Kind.INT, "conid", "conId"),
        r("symbol", Kind.STR),
        r("sec_type", Kind.STR, "secType"),
        r("exchange", Kind.STR),
        r("primary_exchange", Kind.STR, "primaryExch", "primaryExchange"),
        r("currency", Kind.STR),
        r("local_symbol", Kind.STR, "localSymbol"),
        r("trading_class", Kind.STR, "tradingClass"),
        r("last_trade_date", Kind.STR, "lastTradeDateOrContractMonth"),
        r("strike", Kind.DEC),
        r("right", Kind.STR),
        r("multiplier", Kind.STR),
    };

    static final Row[] ORDER = {
        r("action", Kind.STR),
        r("order_type", Kind.STR, "orderType"),
        r("total_quantity", Kind.DEC, "totalQuantity"),
        r("lmt_price", Kind.DEC, "lmtPrice"),
        r("aux_price", Kind.DEC, "auxPrice"),
        r("tif", Kind.STR),
        r("order_ref", Kind.STR, "orderRef"),
        r("account", Kind.STR),
        r("oca_group", Kind.STR, "ocaGroup"),
        r("oca_type", Kind.INT, "ocaType"),
        r("parent_id", Kind.INT, "parentId"),
        r("transmit", Kind.BOOL),
        r("outside_rth", Kind.BOOL, "outsideRth"),
        r("good_till_date", Kind.STR, "goodTillDate"),
        r("what_if", Kind.BOOL, "whatIf"),
    };

    static final Row[] ORDER_STATE = {
        r("status", Kind.STR),
        r("commission", Kind.DEC, "commission", "commissionAndFees"),
        r("commission_currency", Kind.STR, "commissionCurrency", "commissionAndFeesCurrency"),
        r("warning_text", Kind.STR, "warningText"),
        r("init_margin_change", Kind.STR, "initMarginChange"),
        r("maint_margin_change", Kind.STR, "maintMarginChange"),
        r("equity_with_loan_change", Kind.STR, "equityWithLoanChange"),
    };

    static final Row[] EXECUTION = {
        r("exec_id", Kind.STR, "execId"),
        r("account", Kind.STR, "acctNumber", "account"),
        r("exchange", Kind.STR),
        r("side", Kind.STR),
        r("shares", Kind.DEC),
        r("price", Kind.DEC),
        r("perm_id", Kind.INT, "permId"),
        r("client_id", Kind.INT, "clientId"),
        r("order_id", Kind.INT, "orderId"),
        r("liquidation", Kind.INT),
        r("cum_qty", Kind.DEC, "cumQty"),
        r("avg_price", Kind.DEC, "avgPrice"),
        r("order_ref", Kind.STR, "orderRef"),
        r("last_liquidity", Kind.INT, "lastLiquidity"),
    };

    static final Row[] CONTRACT_DETAILS = {
        r("market_name", Kind.STR, "marketName"),
        r("min_tick", Kind.DEC, "minTick"),
        r("order_types", Kind.STR, "orderTypes"),
        r("valid_exchanges", Kind.STR, "validExchanges"),
        r("long_name", Kind.STR, "longName"),
        r("time_zone_id", Kind.STR, "timeZoneId"),
        r("trading_hours", Kind.STR, "tradingHours"),
        r("liquid_hours", Kind.STR, "liquidHours"),
        r("stock_type", Kind.STR, "stockType"),
        r("min_size", Kind.DEC, "minSize"),
        r("size_increment", Kind.DEC, "sizeIncrement"),
        r("market_rule_ids", Kind.STR, "marketRuleIds"),
    };

    static final Row[] COMMISSION = {
        r("exec_id", Kind.STR, "execId"),
        r("commission", Kind.DEC, "commission", "commissionAndFees"),
        r("currency", Kind.STR),
        r("realized_pnl", Kind.DEC, "realizedPNL", "realizedPnl"),
        r("yield", Kind.DEC),
        r("yield_redemption_date", Kind.STR, "yieldRedemptionDate"),
    };

    static final Row[] BAR = {
        r("open", Kind.DEC),
        r("high", Kind.DEC),
        r("low", Kind.DEC),
        r("close", Kind.DEC),
        r("volume", Kind.DEC),
        r("wap", Kind.DEC),
        r("count", Kind.INT),
    };

    private static final String DOUBLE_UNSET = "1.7976931348623157E308";

    /** IB's "unset" sentinels: Double.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, the invalid Decimal. */
    static boolean unset(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Double d) {
            return d == Double.MAX_VALUE || d.isNaN() || d.isInfinite();
        }
        if (v instanceof Integer i) {
            return i == Integer.MAX_VALUE;
        }
        if (v instanceof Long l) {
            return l == Long.MAX_VALUE;
        }
        String s = v.toString();
        return s.isEmpty() || s.equals(DOUBLE_UNSET) || s.equals("Infinity") || s.equals("NaN")
                || s.startsWith("1701411834604692317");
    }

    static String decimalString(Object v) {
        if (v instanceof Double d) {
            return plain(BigDecimal.valueOf(d));
        }
        if (v instanceof Float f) {
            return plain(new BigDecimal(Float.toString(f)));
        }
        if (v instanceof BigDecimal b) {
            return b.toPlainString();
        }
        String s = v.toString().trim();
        try {
            return new BigDecimal(s).toPlainString();
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /** Plain decimal text without a trailing ".0" (IB reports whole numbers as doubles). */
    private static String plain(BigDecimal b) {
        return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
    }

    /** Reads the first alias that yields a value. */
    private static Object read(Object bean, String[] props) {
        for (String p : props) {
            Object v = Reflect.get(bean, p);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Bean to JSON object; unset values are omitted. */
    static Map<String, Object> out(Object bean, Row[] rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (bean == null) {
            return m;
        }
        for (Row row : rows) {
            Object v = read(bean, row.props());
            if (unset(v)) {
                continue;
            }
            if (row.json().equals("strike") && v instanceof Number n && n.doubleValue() == 0.0) {
                continue; // stocks carry a zero strike; only options have a real one
            }
            switch (row.kind()) {
                case STR -> {
                    String s = v instanceof String str ? str : (v instanceof Number ? v.toString() : Reflect.apiString(v));
                    if (s != null && !s.isEmpty()) {
                        m.put(row.json(), s);
                    }
                }
                case INT -> {
                    if (v instanceof Number n) {
                        m.put(row.json(), n.longValue());
                    }
                }
                case DEC -> m.put(row.json(), decimalString(v));
                case BOOL -> {
                    if (v instanceof Boolean b) {
                        m.put(row.json(), b);
                    }
                }
            }
        }
        return m;
    }

    /** JSON object to bean: only keys present in the object are set. */
    static void in(Map<String, Object> json, Row[] rows, Object bean) {
        for (Row row : rows) {
            if (!json.containsKey(row.json())) {
                continue;
            }
            Object v = json.get(row.json());
            if (v == null) {
                continue;
            }
            Object arg = switch (row.kind()) {
                case STR -> v.toString();
                case INT -> v;
                case DEC -> row.json().equals("strike") || row.json().endsWith("price")
                        ? (Object) new BigDecimal(v.toString()).doubleValue()
                        : v.toString();
                case BOOL -> v;
            };
            Reflect.MissingMemberException last = null;
            boolean done = false;
            for (String p : row.props()) {
                try {
                    Reflect.set(bean, p, arg);
                    done = true;
                    break;
                } catch (Reflect.MissingMemberException e) {
                    last = e;
                }
            }
            if (!done) {
                throw last;
            }
        }
    }
}
