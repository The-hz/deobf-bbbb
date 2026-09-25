package io.github.jarremapper.decompiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Locates the standalone decompiler jars on the application class path.
 *
 * <p>At runtime, the app class path (from {@code java.class.path}) contains
 * all Maven-resolved jars — including CFR, Fernflower, and Vineflower —
 * because they are declared as {@code implementation} dependencies in
 * {@code build.gradle}.</p>
 *
 * <p>The locator matches by filename substring (case-insensitive). For
 * example, looking up {@code "vineflower"} should find
 * {@code ~/.gradle/caches/.../vineflower-1.10.1.jar}.</p>
 */
public final class DecompilerLocator {

    private static final Logger LOG = LoggerFactory.getLogger(DecompilerLocator.class);

    private DecompilerLocator() {}

    /**
     * Find a jar whose file name contains {@code substring} (case-insensitive).
     *
     * <p>The locator walks the runtime classpath via two channels:
     * <ol>
     *   <li>The {@code java.class.path} system property (for
     *       {@code java -cp ...} launches — works in Gradle / IDE runs).</li>
     *   <li>{@link ClassLoader#getSystemClassLoader()} URLs (for
     *       URLClassLoader-based launches — works in some IDE setups
     *       where classpath entries aren't exposed as {@code java.class.path}).</li>
     * </ol>
     * This double lookup fixes the IDE-classpath case (P2-19): when running
     * from IntelliJ, {@code java.class.path} may contain class-output
     * directories (e.g. {@code /path/to/out/production/main}) instead of
     * jar files, so the original substring match against {@code *.jar}
     * entries would have failed silently.
     *
     * @param substring the search token (e.g. {@code "cfr"},
     *                 {@code "fernflower"}, {@code "vineflower"})
     * @return the absolute {@link Path} of the first matching class-path entry
     * @throws IOException if no match was found
     */
    public static Path locate(String substring) throws IOException {
        String needle = substring.toLowerCase();

        // Channel 1: java.class.path (CLI / Gradle / Maven launches).
        String cp = System.getProperty("java.class.path", "");
        if (!cp.isEmpty()) {
            String sep = System.getProperty("path.separator", ":");
            for (String entry : cp.split(sep)) {
                if (entry.isBlank()) continue;
                Path p = Path.of(entry);
                String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
                if (name.contains(needle) && name.endsWith(".jar") && Files.exists(p)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Matched '{}' against java.class.path entry '{}'", needle, p);
                    }
                    return p.toAbsolutePath();
                }
            }
        }

        // Channel 2: ClassLoader URLs (IDE launches).
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        if (sys instanceof java.net.URLClassLoader ucl) {
            for (java.net.URL url : ucl.getURLs()) {
                if (!"file".equals(url.getProtocol())) continue;
                Path p = Path.of(url.getPath());
                String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
                if (name.contains(needle) && name.endsWith(".jar") && Files.exists(p)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Matched '{}' against ClassLoader URL '{}'", needle, p);
                    }
                    return p.toAbsolutePath();
                }
            }
        }

        // Channel 3: scan ./lib/ for manually-dropped jars (e.g. JetBrains Fernflower).
        Path libDir = Path.of(System.getProperty("user.dir"), "lib");
        if (Files.isDirectory(libDir)) {
            try (var stream = Files.list(libDir)) {
                var match = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
                            return n.contains(needle) && n.endsWith(".jar");
                        })
                        .findFirst();
                if (match.isPresent()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Matched '{}' against ./lib/ entry '{}'", needle, match.get());
                    }
                    return match.get().toAbsolutePath();
                }
            } catch (IOException e) {
                LOG.warn("Could not list ./lib/: {}", e.toString());
            }
        }

        throw new IOException("Could not locate a decompiler jar matching '" + substring +
                "' on the runtime class path (java.class.path='" + cp +
                "', system class loader URLs were also checked). " +
                "Add the corresponding implementation dependency in build.gradle, " +
                "or drop the jar into ./lib/.");
    }

    /**
     * Locate all three known decompilers; the result map keys are the
     * adapter short names ("CFR", "Fernflower", "Vineflower").
     */
    public static java.util.Map<String, Path> locateAll() {
        java.util.Map<String, Path> out = new java.util.LinkedHashMap<>();
        try { out.put("CFR",         locate("cfr")); } catch (IOException e) { LOG.warn("CFR jar not found: {}", e.getMessage()); }
        try { out.put("Fernflower", locate("fernflower")); } catch (IOException e) { LOG.warn("Fernflower jar not found: {}", e.getMessage()); }
        try { out.put("Vineflower", locate("vineflower")); } catch (IOException e) { LOG.warn("Vineflower jar not found: {}", e.getMessage()); }
        return out;
    }
}
