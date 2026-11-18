// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Process contract: exit codes, and the real class loading path against a jar that holds the IB test double. */
class MainTest {
    @TempDir Path dir;

    static final String[] FAKE_CLASSES = {"Decimal", "Contract", "Order", "OrderState", "Execution", "ExecutionFilter",
        "CommissionReport", "TickAttrib", "EWrapper", "EJavaSignal", "EReader", "EClientSocket"};

    static int run(String... args) {
        return Main.run(args, new PrintStream(new ByteArrayOutputStream()));
    }

    Path fakeApiJar() throws IOException {
        Path jar = dir.resolve("TwsApi.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            Path classesDir = Path.of(java.net.URLDecoder.decode(MainTest.class.getClassLoader().getResource("com/ib/client/EClientSocket.class").getPath(), java.nio.charset.StandardCharsets.UTF_8)).getParent();
            try (var files = Files.list(classesDir)) {
                for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".class"))::iterator) {
                    out.putNextEntry(new JarEntry("com/ib/client/" + f.getFileName()));
                    Files.copy(f, out);
                    out.closeEntry();
                }
            }
        }
        return jar;
    }

    @Test
    void badArgumentsExitWith2() {
        assertEquals(Main.EXIT_ARGS, run());
        assertEquals(Main.EXIT_ARGS, run("--socket", "/tmp/x"));
        assertEquals(Main.EXIT_ARGS, run("--api-jar", "/tmp/x"));
        assertEquals(Main.EXIT_ARGS, run("--socket", "/tmp/x", "--api-jar", "/tmp/y", "--bogus", "1"));
        assertEquals(Main.EXIT_ARGS, run("--socket", "/tmp/x", "--api-jar", "/tmp/y", "--log-level", "loud"));
        assertEquals(Main.EXIT_ARGS, run("--socket"));
    }

    @Test
    void missingOrWrongApiJarExitsWith3() throws Exception {
        assertEquals(Main.EXIT_API_JAR, run("--socket", dir.resolve("s").toString(), "--api-jar", dir.resolve("nope.jar").toString()));
        Path junk = dir.resolve("junk.jar");
        Files.writeString(junk, "not a jar");
        assertEquals(Main.EXIT_API_JAR, run("--socket", dir.resolve("s").toString(), "--api-jar", junk.toString()));
        // a valid jar without IB's client class
        Path empty = dir.resolve("empty.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(empty))) {
            out.putNextEntry(new JarEntry("readme.txt"));
            out.write(1);
        }
        assertEquals(Main.EXIT_API_JAR, run("--socket", dir.resolve("s").toString(), "--api-jar", empty.toString()));
    }

    @Test
    void aMissingSecondJarOfAClassPathStyleValueExitsWith3() throws Exception {
        Path jar = fakeApiJar();
        String both = jar + java.io.File.pathSeparator + dir.resolve("protobuf.jar");
        assertEquals(Main.EXIT_API_JAR, run("--socket", dir.resolve("s").toString(), "--api-jar", both));
    }

    @Test
    void unusableSocketPathExitsWith4() throws Exception {
        Path jar = fakeApiJar();
        assertEquals(Main.EXIT_SOCKET, run("--socket", dir.resolve("missing/dir/s.sock").toString(), "--api-jar", jar.toString()));
    }

    /** Runs the shim as a real process with only main classes on its class path: IB's classes come from --api-jar. */
    @Test
    void realProcessServesHelloLoadsApiFromJarAndExitsZeroOnSigterm() throws Exception {
        Path jar = fakeApiJar();
        Path sock = dir.resolve("p.sock");
        Path mainClasses = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-cp", mainClasses.toString(), "org.perkbridge.ibshim.Main",
                "--socket", sock.toString(), "--api-jar", jar.toString(), "--log-level", "info"));
        Path logFile = dir.resolve("shim.log");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(logFile.toFile());
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("_JAVA_OPTIONS");
        Process p = pb.start();
        String out;
        try {
            for (int i = 0; i < 100 && !Files.exists(sock); i++) {
                TimeUnit.MILLISECONDS.sleep(100);
            }
            assertTrue(Files.exists(sock), "socket must appear");
            try (TestClient c = new TestClient(sock)) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> res = (java.util.Map<String, Object>)
                        c.call("hello", "{\"protocol\":\"perkbridge.ibshim/v1\",\"client\":\"t\"}").get("result");
                assertEquals("client_version=66", res.get("api_client"));
                assertTrue(((List<?>) res.get("ops")).contains("place_order"));
                // IB classes came from the jar through the adapter loader: a connect reaches the test double
                try (java.net.ServerSocket gw = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
                    java.util.Map<String, Object> cn = c.call("connect",
                            "{\"host\":\"127.0.0.1\",\"port\":" + gw.getLocalPort() + ",\"client_id\":1}");
                    assertEquals(Boolean.TRUE, cn.get("ok"), String.valueOf(cn));
                }
            }
            p.destroy(); // SIGTERM
            assertTrue(p.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, p.exitValue(), "SIGTERM is a clean stop");
            assertTrue(!Files.exists(sock), "socket file is removed on stop");
            out = Files.readString(logFile);
        } finally {
            p.destroyForcibly();
        }
        assertTrue(out.contains("\"msg\":\"listening\""), out);
        assertTrue(out.lines().allMatch(l -> l.startsWith("{") && l.endsWith("}")), "stderr is JSON lines only: " + out);
    }
}
