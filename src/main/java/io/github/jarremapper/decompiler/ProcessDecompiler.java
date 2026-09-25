package io.github.jarremapper.decompiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
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
 * Common scaffolding for decompilers that ship a {@code main(String[])} entry
 * point and can be run as a separate JVM process.
 *
 * <p>Spawning a child JVM:
 * <ul>
 *   <li>isolates decompiler jars that share package names (Fernflower /
 *       Vineflower both live under {@code org.jetbrains.java.decompiler});</li>
 *   <li>shields the GUI process from any {@code System.exit()} the
 *       decompiler may invoke on completion;</li>
 *   <li>lets us capture stdout/stderr into our log sink without
 *       interfering with the GUI's own console.</li>
 * </ul>
 *
 * <p>The classpath passed to the child is <em>only</em> the decompiler jar —
 * the bundled dependencies of each decompiler live inside that jar (CFR,
 * Vineflower, Fernflower all shade their dependencies for this reason).</p>
 */
public abstract class ProcessDecompiler implements Decompiler {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessDecompiler.class);

    /** Child-JVM timeout in minutes (default 15; configurable via
     * {@link io.github.jarremapper.config.AppConfig#decompilerTimeoutMinutes()}). */
    private final long timeoutMinutes;

    protected ProcessDecompiler() {
        this(15);
    }

    protected ProcessDecompiler(long timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes <= 0 ? 15 : timeoutMinutes;
    }

    /** Subclasses turn options + paths into CLI args. */
    protected abstract String[] buildArgs(Path input, Path outputDir, Map<String, String> options);

    @Override
    public void decompile(Path input, Path outputDir, Map<String, String> options) throws Exception {
        if (!Files.isRegularFile(input)) {
            throw new IOException("Input not a regular file: " + input);
        }
        Files.createDirectories(outputDir);
        // Some decompilers refuse to write into a non-empty dir.
        ensureEmptyOrClean(outputDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        // JVM tuning: ensure UTF-8 + a generous heap.
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Xmx1g");
        cmd.add("-cp");
        cmd.add(jarPath().toString());
        cmd.add(mainClassName());
        String[] args = buildArgs(input, outputDir, options);
        for (String a : args) cmd.add(a);

        LOG.info("Spawning {} decompiler: {}", name(), String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process proc = pb.start();
        // Drain stdout/stderr in a background thread.
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    LOG.info("[{}] {}", name(), line);
                }
            } catch (IOException e) {
                LOG.warn("Error reading {} stdout: {}", name(), e.toString());
            }
        }, "decompiler-" + name().toLowerCase() + "-stdout");
        drain.setDaemon(true);
        drain.start();

        boolean finished = proc.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException(name() + " decompiler timed out after " + timeoutMinutes + " min");
        }
        int code = proc.exitValue();
        drain.join(2000);
        if (code != 0) {
            throw new IOException(name() + " decompiler exited with code " + code);
        }
        LOG.info("{} decompiler finished.", name());
    }

    private static void ensureEmptyOrClean(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        // P1-8: refuse to recursively delete anything outside the current
        // working directory or the system temp directory. This protects
        // against accidental misuse of the API (e.g. someone passing
        // outputDir = ~ or /).
        Path canonical = dir.toAbsolutePath().normalize();
        Path cwd       = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path tmp       = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!canonical.startsWith(cwd) && !canonical.startsWith(tmp)) {
            throw new IOException("Refuse to clean dir outside CWD/temp: " + canonical +
                    " (cwd=" + cwd + ", tmp=" + tmp + ")");
        }
        // Also refuse to delete the cwd or tmp root themselves.
        if (canonical.equals(cwd) || canonical.equals(tmp)) {
            throw new IOException("Refuse to clean the CWD or temp root: " + canonical);
        }
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) return;
        }
        // Best-effort recursive delete of the dir's contents.
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .filter(p -> !p.equals(dir))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                  });
        } catch (IOException e) {
            LOG.warn("Could not clean output dir {}: {}", dir, e.toString());
        }
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("java.home is not set");
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String exe = os.contains("win") ? "java.exe" : "java";
        Path p = Path.of(home, "bin", exe);
        if (!Files.exists(p)) {
            throw new IllegalStateException("java executable not found at " + p);
        }
        return p.toAbsolutePath().toString();
    }
}
