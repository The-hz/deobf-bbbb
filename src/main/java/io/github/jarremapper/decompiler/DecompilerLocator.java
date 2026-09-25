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
     * @param substring the search token (e.g. {@code "cfr"},
     *                 {@code "fernflower"}, {@code "vineflower"})
     * @return the absolute {@link Path} of the first matching class-path entry
     * @throws IOException if no match was found
     */
    public static Path locate(String substring) throws IOException {
        String needle = substring.toLowerCase();
        String cp = System.getProperty("java.class.path", "");
        if (cp.isEmpty()) {
            throw new IOException("java.class.path is empty; cannot locate decompiler jars.");
        }
        String sep = System.getProperty("path.separator", ":");
        for (String entry : cp.split(sep)) {
            if (entry.isBlank()) continue;
            Path p = Path.of(entry);
            String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
            if (name.contains(needle) && name.endsWith(".jar")) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Matched '{}' against entry '{}'", needle, p);
                }
                return p.toAbsolutePath();
            }
        }
        throw new IOException("Could not locate a decompiler jar matching '" + substring +
                "' in class path. Class path was: " + cp);
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
