package io.github.jarremapper.decompiler;

import java.nio.file.Path;
import java.util.Map;

/**
 * Common SPI for the embedded decompilers (CFR / Fernflower / Vineflower).
 *
 * <p>Each adapter isolates its underlying decompiler jar in a child JVM
 * process to avoid classpath conflicts — Fernflower and Vineflower both
 * publish under the {@code org.jetbrains.java.decompiler} package and
 * cannot co-exist in the same classloader.</p>
 */
public interface Decompiler {

    /** Short, GUI-friendly name (e.g. {@code "CFR"}). */
    String name();

    /** Path to the underlying decompiler jar. */
    Path jarPath();

    /** Fully-qualified main-class name (used to launch the child JVM). */
    String mainClassName();

    /**
     * Decompile {@code input} (a JAR file or a single {@code .class} file)
     * into {@code outputDir}.
     *
     * @param input    the input JAR / .class file
     * @param outputDir the target directory; will be created if absent
     * @param options  free-form key/value options that the adapter may map
     *                 to decompiler-specific CLI flags
     */
    void decompile(Path input, Path outputDir, Map<String, String> options) throws Exception;
}
