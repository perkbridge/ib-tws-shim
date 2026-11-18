// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point. {@code java -jar ib-tws-shim.jar --socket PATH --api-jar PATH
 * [--log-level debug|info|warn|error]}. Exit codes: 0 on SIGTERM, 2 bad
 * arguments, 3 API jar missing or unloadable, 4 socket path unusable.
 */
public final class Main {
    public static final int EXIT_OK = 0;
    public static final int EXIT_ARGS = 2;
    public static final int EXIT_API_JAR = 3;
    public static final int EXIT_SOCKET = 4;
    public static final int EXIT_INTERNAL = 1;

    private static volatile boolean failing;

    private Main() {}

    public static void main(String[] argv) {
        int code = run(argv, System.err);
        // run() only returns for a failure; a clean stop halts from the hook
        System.exit(code);
    }

    static int run(String[] argv, PrintStream err) {
        String socket = null;
        String apiJar = null;
        Log.Level level = Log.Level.INFO;
        try {
            for (int i = 0; i < argv.length; i++) {
                String a = argv[i];
                String v;
                int eq = a.indexOf('=');
                if (a.startsWith("--") && eq > 0) {
                    v = a.substring(eq + 1);
                    a = a.substring(0, eq);
                } else if (i + 1 < argv.length) {
                    v = argv[i + 1];
                    i++;
                } else {
                    throw new IllegalArgumentException("missing value for " + a);
                }
                switch (a) {
                    case "--socket" -> socket = v;
                    case "--api-jar" -> apiJar = v;
                    case "--log-level" -> level = Log.Level.parse(v);
                    default -> throw new IllegalArgumentException("unknown argument " + a);
                }
            }
            if (socket == null || socket.isEmpty()) {
                throw new IllegalArgumentException("--socket is required");
            }
            if (apiJar == null || apiJar.isEmpty()) {
                throw new IllegalArgumentException("--api-jar is required");
            }
        } catch (IllegalArgumentException e) {
            Log log = new Log(Log.Level.INFO, err);
            log.error("bad_arguments", "error", e.getMessage(),
                    "usage", "--socket PATH --api-jar PATH [--log-level debug|info|warn|error]");
            return EXIT_ARGS;
        }
        Log log = new Log(level, err);

        Venue venue;
        try {
            venue = VenueLoader.load(apiJar, log);
        } catch (VenueLoader.ApiJarException e) {
            log.error("api_jar_unusable", "path", apiJar, "error", e.getMessage());
            return EXIT_API_JAR;
        }

        ServerConfig cfg = new ServerConfig(Path.of(socket));
        String ver = Main.class.getPackage() == null ? null : Main.class.getPackage().getImplementationVersion();
        if (ver != null) {
            cfg.shimVersion = "ib-tws-shim/" + ver;
        }
        Server server = new Server(cfg, venue, log);
        try {
            server.bind();
        } catch (Server.SocketUnusableException e) {
            log.error("socket_unusable", "error", e.getMessage());
            return EXIT_SOCKET;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (failing) {
                return; // an error exit keeps its exit code
            }
            log.info("shutdown", "signal", "SIGTERM");
            server.close();
            Runtime.getRuntime().halt(EXIT_OK); // SIGTERM is a clean stop: exit 0
        }, "ibshim-shutdown"));
        log.info("started", "shim", cfg.shimVersion, "api_jar", apiJar, "java", System.getProperty("java.version"));
        server.serve();
        if (!server.closing()) {
            failing = true;
            log.error("serve_stopped", "error", "the accept loop ended without a stop request");
            return EXIT_INTERNAL;
        }
        return EXIT_OK;
    }
}
