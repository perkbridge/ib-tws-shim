// SPDX-License-Identifier: GPL-3.0-or-later
package org.perkbridge.ibshim;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

/**
 * Loads the IB venue with IB's {@code TwsApi.jar} on its class path. The shim
 * jar contains none of IB's classes: the jar named by {@code --api-jar} is
 * added at run time through a loader that takes the adapter ({@code ...ib.*})
 * and IB's classes from the jars but shares this package's core types with the
 * application loader.
 */
final class VenueLoader {
    static final String VENUE_CLASS = "org.perkbridge.ibshim.ib.IbVenue";
    static final String IB_PROBE_CLASS = "com.ib.client.EClientSocket";

    /** The API jar is missing or does not hold what the adapter needs (exit 3). */
    static final class ApiJarException extends Exception {
        private static final long serialVersionUID = 1L;

        ApiJarException(String m, Throwable cause) {
            super(m, cause);
        }
    }

    private VenueLoader() {}

    /**
     * @param apiJars the value of {@code --api-jar}: one path, or several separated by the
     *     platform path separator (newer IB libraries need extra jars such as protobuf)
     */
    static Venue load(String apiJars, Log log) throws ApiJarException {
        java.util.List<URL> urls = new java.util.ArrayList<>();
        try {
            CodeSource cs = VenueLoader.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                urls.add(cs.getLocation());
            }
            int given = 0;
            for (String p : apiJars.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (p.isEmpty()) {
                    continue;
                }
                Path jar = Path.of(p);
                if (!Files.isRegularFile(jar) || !Files.isReadable(jar)) {
                    throw new ApiJarException(p + ": not a readable file", null);
                }
                urls.add(jar.toUri().toURL());
                given++;
            }
            if (given == 0) {
                throw new ApiJarException("no API jar given", null);
            }
            ClassLoader app = VenueLoader.class.getClassLoader();
            AdapterLoader loader = new AdapterLoader(urls.toArray(new URL[0]), app);
            // Link, not just find, the classes the adapter relies on: a missing dependency of
            // IB's library (for example a protobuf class used in EWrapper) fails here, at
            // start, with exit code 3, instead of at the first connect.
            for (String n : new String[] {"com.ib.client.EClientSocket", "com.ib.client.EWrapper",
                "com.ib.client.EReader", "com.ib.client.EJavaSignal", "com.ib.client.Contract",
                "com.ib.client.Order", "com.ib.client.ExecutionFilter"}) {
                Class<?> c = Class.forName(n, false, loader);
                c.getMethods();
                c.getConstructors();
            }
            Class<?> c = Class.forName(VENUE_CLASS, true, loader);
            Object o = c.getDeclaredConstructor(Log.class).newInstance(log);
            return (Venue) o;
        } catch (IOException | ReflectiveOperationException | LinkageError | ClassCastException e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause()
                    : e;
            throw new ApiJarException(t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    /**
     * Child-first for the adapter and IB's classes, parent-first for the JDK
     * and for the shim core, so that {@code Venue}, {@code Sink} and friends
     * are one class on both sides.
     */
    private static final class AdapterLoader extends URLClassLoader {
        private static final String CORE = "org.perkbridge.ibshim.";
        private static final String ADAPTER = "org.perkbridge.ibshim.ib.";

        AdapterLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                boolean mine = name.startsWith(ADAPTER) || name.startsWith("com.ib.");
                if (!mine) {
                    return super.loadClass(name, resolve);
                }
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = findClass(name);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }
}
