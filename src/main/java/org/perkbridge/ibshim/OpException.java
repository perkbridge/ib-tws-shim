// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

/** A request failure carrying a protocol error code (docs: "Error codes"). */
public final class OpException extends Exception {
    private static final long serialVersionUID = 1L;

    public static final String BAD_REQUEST = "bad_request";
    public static final String UNSUPPORTED_OP = "unsupported_op";
    public static final String NOT_CONNECTED = "not_connected";
    public static final String ALREADY_CONNECTED = "already_connected";
    public static final String BUSY = "busy";
    public static final String TIMEOUT = "timeout";
    public static final String VENUE_ERROR = "venue_error";
    public static final String INTERNAL = "internal";

    private final String code;
    private final int venueCode;

    public OpException(String code, String message) {
        this(code, message, 0);
    }

    public OpException(String code, String message, int venueCode) {
        super(message);
        this.code = code;
        this.venueCode = venueCode;
    }

    public String code() {
        return code;
    }

    public int venueCode() {
        return venueCode;
    }
}
