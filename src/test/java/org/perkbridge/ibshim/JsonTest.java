// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void parsesObjectsAndKeepsNumberKinds() throws Exception {
        Map<String, Object> m = Json.parseObject("{\"a\":1,\"b\":\"x\\n\\u00e9\",\"c\":[true,null,1.5],\"d\":{\"e\":-2},\"f\":12345678901234567890}");
        assertEquals(1L, m.get("a"));
        assertEquals("x\n\u00e9", m.get("b"));
        assertEquals(List.of(true, 1.5).size() + 1, ((List<?>) m.get("c")).size());
        assertEquals(new BigDecimal("1.5"), ((List<?>) m.get("c")).get(2));
        assertEquals(-2L, ((Map<?, ?>) m.get("d")).get("e"));
        assertEquals(new BigDecimal("12345678901234567890"), m.get("f"));
    }

    @Test
    void roundTripsAndEscapes() throws Exception {
        String s = Json.write(Map.of("k", "a\"b\\c\u0001\u2028"));
        assertEquals("a\"b\\c\u0001\u2028", Json.parseObject(s).get("k"));
        assertTrue(s.contains("\\u0001") && s.contains("\\u2028"));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[] {"", "{", "{\"a\":}", "{\"a\":1,}", "[1,]", "{\"a\":1} x", "{\"a\":01x}", "{'a':1}",
            "{\"a\":1,\"a\":2}", "\"\u0001\"", "[", "{\"a\":\"\\q\"}", "nul", "{\"a\":1.}"}) {
            assertThrows(Json.ParseException.class, () -> Json.parse(bad), bad);
        }
        assertThrows(Json.ParseException.class, () -> Json.parseObject("[1]"));
        assertThrows(Json.ParseException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
    }

    @Test
    void writesDecimalsPlain() {
        java.util.List<Object> l = new java.util.ArrayList<>(java.util.Arrays.asList(1L, "x", null, true, new BigDecimal("0.0001")));
        assertEquals("[1,\"x\",null,true,0.0001]", Json.write(l));
        assertEquals("100000000000", Json.write(new BigDecimal("1E+11")));
        assertEquals("null", Json.write(Double.NaN));
    }
}
