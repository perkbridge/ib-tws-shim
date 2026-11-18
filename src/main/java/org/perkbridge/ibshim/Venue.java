// SPDX-License-Identifier: GPL-3.0-only
package org.perkbridge.ibshim;

import java.util.List;
import java.util.Map;

/**
 * The venue behind the protocol. The core knows framing, sessions, sequencing
 * and flow control; a venue translates ops into calls on the real client and
 * callbacks into events. Calls on one venue are serialised by the session.
 */
public interface Venue {
    /** Version string of the underlying client library, for {@code hello}. */
    String apiClientVersion();

    /** Ops this venue implements (the core adds {@code hello} and {@code ping}). */
    List<String> ops();

    /** True while a gateway connection exists (reported in {@code heartbeat}). */
    boolean connected();

    /** A client connected: events from now on go to the sink. */
    void attach(Sink sink);

    /**
     * Runs one op. {@code args} is never null. Returns the result object.
     *
     * @throws OpException for a protocol-level failure
     */
    Map<String, Object> handle(String op, Map<String, Object> args) throws OpException;

    /**
     * The client is gone: disconnect from the gateway, forget every
     * subscription and discard unsent state. Must not throw.
     */
    void detach();
}
