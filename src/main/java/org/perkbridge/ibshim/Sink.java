// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

import java.util.Map;

/** Where a venue puts events. The session stamps {@code seq} and {@code at}. */
public interface Sink {
    /**
     * Queues an event for the connected client.
     *
     * @return the {@code seq} given to the event, or 0 when it was discarded
     *     because no client is connected any more
     */
    long event(String name, Map<String, Object> data);
}
