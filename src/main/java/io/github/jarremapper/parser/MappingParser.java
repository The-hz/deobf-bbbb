package io.github.jarremapper.parser;

import io.github.jarremapper.model.MappingModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * SPI for mapping-file format parsers.
 *
 * <p>Implementations are expected to be stateless and thread-safe enough to
 * be called from the GUI thread; they are <em>not</em> required to be
 * concurrent-safe across calls on the same instance.</p>
 */
public interface MappingParser {

    /** Stable, lowercase, no-space identifier (e.g. {@code "tiny-v2"}). */
    String formatId();

    /** Human-readable name shown in the GUI (e.g. {@code "Tiny v2"}). */
    default String displayName() { return formatId(); }

    /**
     * Sniff-test the file by its first few lines (UTF-8).
     *
     * <p>Implementations should be tolerant of BOM and trailing whitespace.
     * The framework reads the file once and passes the first ~8 lines here.</p>
     */
    boolean matches(List<String> firstLines);

    /**
     * Parse the entire file into a fresh {@link MappingModel}.
     *
     * @throws IOException if reading fails
     * @throws IllegalArgumentException if the file content does not look like
     *         this parser's format (an explanatory message should be
     *         provided)
     */
    MappingModel parse(Path file) throws IOException;
}
