// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Listens on the unix socket and serves one client at a time. */
public final class Server implements AutoCloseable {
    static final byte[] BUSY = "{\"ok\":false,\"error\":{\"code\":\"busy\"}}\n".getBytes(StandardCharsets.UTF_8);

    /** Socket path unusable (exit code 4). */
    public static final class SocketUnusableException extends IOException {
        private static final long serialVersionUID = 1L;

        public SocketUnusableException(String m, Throwable cause) {
            super(m, cause);
        }
    }

    private final ServerConfig cfg;
    private final Venue venue;
    private final Log log;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicReference<Session> active = new AtomicReference<>();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ibshim-heartbeat");
                t.setDaemon(true);
                return t;
            });
    private ServerSocketChannel server;
    private volatile boolean closing;

    /** True once {@link #close()} was called (a stop was requested). */
    public boolean closing() {
        return closing;
    }

    public Server(ServerConfig cfg, Venue venue, Log log) {
        this.cfg = cfg;
        this.venue = venue;
        this.log = log;
    }

    /** Binds the socket (removing a stale one) and applies mode 0660. */
    public void bind() throws SocketUnusableException {
        Path p = cfg.socketPath;
        try {
            Path dir = p.toAbsolutePath().getParent();
            if (dir == null || !Files.isDirectory(dir) || !Files.isWritable(dir)) {
                throw new IOException("directory " + dir + " is missing or not writable");
            }
            if (Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(p) || Files.isSymbolicLink(p) || Files.isRegularFile(p)) {
                    throw new IOException(p + " exists and is not a socket");
                }
                // a socket file: refuse when something still answers on it
                boolean live;
                try (SocketChannel probe = SocketChannel.open(UnixDomainSocketAddress.of(p))) {
                    live = probe.isOpen();
                } catch (IOException e) {
                    live = false;
                }
                if (live) {
                    throw new IOException("another process is listening on " + p);
                }
                Files.deleteIfExists(p);
            }
            // The socket is created inside a private 0700 directory, given mode 0660 and then
            // renamed into place, so nobody can connect while its mode still follows the umask.
            Path tmpDir = Files.createTempDirectory(dir, ".ibshim-",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path tmp = tmpDir.resolve("s");
            try {
                server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                server.bind(UnixDomainSocketAddress.of(tmp));
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-rw----"));
                Files.move(tmp, p, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
                Files.deleteIfExists(tmpDir);
            }
            try {
                if (Files.getPosixFilePermissions(dir).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE)
                        || Files.getPosixFilePermissions(dir).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
                    log.warn("socket_dir_world_accessible", "dir", dir.toString());
                }
            } catch (UnsupportedOperationException | IOException ignored) {
                // no POSIX view: nothing to check
            }
        } catch (IOException | RuntimeException e) {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // closing after failure
                }
            }
            throw new SocketUnusableException("cannot use socket " + p + ": " + e.getMessage(), e);
        }
        log.info("listening", "socket", p.toString());
    }

    /** Accept loop; returns after {@link #close()}. */
    public void serve() {
        try {
            timer.scheduleAtFixedRate(() -> {
                Session s = active.get();
                if (s != null && !s.finished()) {
                    try {
                        s.expireIfNoHello();
                    s.heartbeat();
                    } catch (RuntimeException e) {
                        log.error("heartbeat_failed", "error", e.toString());
                    }
                }
            }, cfg.heartbeatMillis, cfg.heartbeatMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return; // closed before serving started
        }
        while (!closing) {
            SocketChannel c;
            try {
                c = server.accept();
            } catch (IOException e) {
                if (!closing) {
                    log.error("accept_failed", "error", e.toString());
                }
                return;
            }
            Session cur = active.get();
            if (cur != null && !cur.finished()) {
                refuseBusy(c);
                continue;
            }
            Session s = new Session(c, venue, seq, log, cfg);
            active.set(s);
            log.info("client_connected");
            Thread t = new Thread(s::run, "ibshim-session");
            t.setDaemon(true);
            t.start();
        }
    }

    private void refuseBusy(SocketChannel c) {
        try (c) {
            c.write(ByteBuffer.wrap(BUSY));
            log.info("client_refused_busy");
        } catch (IOException e) {
            log.debug("busy_write_failed", "error", e.toString());
        }
    }

    @Override
    public void close() {
        closing = true;
        timer.shutdownNow();
        Session s = active.get();
        if (s != null) {
            s.close("shutdown");
        }
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        try {
            Files.deleteIfExists(cfg.socketPath);
        } catch (NoSuchFileException ignored) {
            // gone already
        } catch (IOException e) {
            log.warn("socket_cleanup_failed", "error", e.toString());
        }
        if (s != null) {
            for (int i = 0; i < 40 && !s.finished(); i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
