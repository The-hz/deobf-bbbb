package io.github.jarremapper.parser;

import io.github.jarremapper.model.MappingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry of all built-in mapping parsers. Used by the GUI / pipeline to
 * auto-detect the format of the mapping file dropped into drop zone #2.
 *
 * <p>The order in {@link #parsers()} matters: more specific matchers run first
 * so that e.g. a Tiny v2 file is never misclassified as Tiny v1.</p>
 */
public final class MappingParsers {

    private static final Logger LOG = LoggerFactory.getLogger(MappingParsers.class);

    private MappingParsers() {}

    private static final List<MappingParser> REGISTRY = List.of(
            new io.github.jarremapper.parser.impl.TinyV2Parser(),
            new io.github.jarremapper.parser.impl.TinyV1Parser(),
            new io.github.jarremapper.parser.impl.TsrgV2Parser(),
            new io.github.jarremapper.parser.impl.TsrgV1Parser(),
            new io.github.jarremapper.parser.impl.SrgParser(),
            new io.github.jarremapper.parser.impl.MojangParser()
    );

    /** Immutable list of registered parsers (ordered). */
    public static List<MappingParser> parsers() {
        return REGISTRY;
    }

    /** Read the first up-to-{@code n} lines of a file as UTF-8. */
    public static List<String> headLines(Path file, int n) throws IOException {
        List<String> out = new ArrayList<>(n);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < n) {
                out.add(line);
                count++;
            }
        }
        return out;
    }

    /**
     * Detect and parse a mapping file. Returns a populated
     * {@link MappingModel} or throws.
     */
    public static MappingModel parse(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Not a regular file: " + file);
        }
        List<String> head = headLines(file, 8);
        for (MappingParser p : REGISTRY) {
            try {
                if (p.matches(head)) {
                    LOG.info("Detected mapping format '{}' for {}", p.formatId(), file);
                    return p.parse(file);
                }
            } catch (RuntimeException ex) {
                LOG.warn("Parser {} rejected {}: {}", p.formatId(), file, ex.toString());
            }
        }
        throw new IllegalArgumentException(
                "Could not auto-detect mapping format for " + file +
                ". Supported formats: tiny-v2, tiny-v1, tsrg-v2, tsrg-v1, srg, mojang (proguard).");
    }
}
