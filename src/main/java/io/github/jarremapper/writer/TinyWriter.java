package io.github.jarremapper.writer;

import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.model.MemberKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serializes a {@link MappingModel} as a Tiny v2 mapping file.
 *
 * <p>Output layout (tab-separated):
 * <pre>
 * tiny	2	0	<obf-namespace>	<named-namespace>
 * c	<obf-class>	<named-class>
 * 	m	<obf-method-name>	<obf-desc>	<named-method>
 * 	f	<obf-field-name>	<obf-desc>	<named-field>
 * </pre>
 *
 * <p>Identity entries (where the user chose "Skip" → {@code aaa → aaa}) are
 * emitted verbatim — this is the contract from the user requirements so that
 * downstream tools (Fabric Loader, loom, etc.) have a complete mapping with
 * no missing rows.</p>
 */
public class TinyWriter {

    private static final Logger LOG = LoggerFactory.getLogger(TinyWriter.class);

    /** Tiny format major version. */
    public static final int MAJOR = 2;
    /** Tiny format minor version. */
    public static final int MINOR = 0;

    private final MappingModel model;

    public TinyWriter(MappingModel model) {
        this.model = model;
    }

    /** Write the file to {@code outputPath}. */
    public void write(Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        int classCount = 0, methodCount = 0, fieldCount = 0;
        try (BufferedWriter w = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            // Header
            w.write("tiny");
            w.write('\t'); w.write(Integer.toString(MAJOR));
            w.write('\t'); w.write(Integer.toString(MINOR));
            w.write('\t'); w.write(escape(model.obfNamespace()));
            w.write('\t'); w.write(escape(model.namedNamespace()));
            w.newLine();

            // Each class
            for (MappingModel.ClassEntry ce : model.entries()) {
                // Skip classes with no obf->orig mapping AND no members (rare, but possible).
                if (ce.obfName() == null || ce.obfName().isBlank()) continue;
                w.write("c");
                w.write('\t'); w.write(escape(ce.obfName()));
                w.write('\t'); w.write(escape(ce.origName()));
                w.newLine();
                classCount++;

                // Methods
                for (Map.Entry<MemberKey, String> me : ce.methods().entrySet()) {
                    w.write("\tm");
                    w.write('\t'); w.write(escape(me.getKey().name()));
                    w.write('\t'); w.write(escape(me.getKey().descriptor()));
                    w.write('\t'); w.write(escape(me.getValue()));
                    w.newLine();
                    methodCount++;
                }
                // Fields
                for (Map.Entry<MemberKey, String> fe : ce.fields().entrySet()) {
                    w.write("\tf");
                    w.write('\t'); w.write(escape(fe.getKey().name()));
                    w.write('\t'); w.write(escape(fe.getKey().descriptor()));
                    w.write('\t'); w.write(escape(fe.getValue()));
                    w.newLine();
                    fieldCount++;
                }
            }
        }
        LOG.info("Tiny v2 written: {} class(es), {} method(s), {} field(s) → {}", classCount,
                methodCount, fieldCount, outputPath);
    }

    /** Escape any tab/newline characters in a name so the format stays tab-aligned. */
    private static String escape(String s) {
        if (s == null) return "";
        // The format is tab-separated; we only need to escape control chars.
        // Names should normally not contain tabs or newlines, but be safe.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
