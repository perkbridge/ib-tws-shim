// SPDX-License-Identifier: GPL-3.0-only
package org.perkbridge.ibshim;

import java.nio.file.Path;

/** Server settings; the spec's values are the defaults. */
public final class ServerConfig {
    public final Path socketPath;
    public String shimVersion = "ib-tws-shim/0.3.0";
    public int maxLineBytes = 1 << 20;
    public long maxQueueBytes = 64L << 20;
    public long heartbeatMillis = 5000;
    public long helloTimeoutMillis = 30_000;

    public ServerConfig(Path socketPath) {
        this.socketPath = socketPath;
    }
}
