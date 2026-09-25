package io.github.jarremapper.parser.impl;

import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.parser.MappingParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Classic SRG mapping parser.
 *
 * <p>Each non-comment line begins with one of:
 * <ul>
 *   <li><b>{@code CL: obf orig}</b> — class mapping</li>
 *   <li><b>{@code FD: obf/field orig/field}</b> — field mapping (the
 *       descriptor is implicit in the qualified path; we look it up later
 *       in the applier from the JAR's class info)</li>
 *   <li><b>{@code MD: obf/method desc orig/method desc}</b> — method
 *       mapping (descriptor is given explicitly)</li>
 * </ul>
 *
 * <p>For fields, we leave the descriptor empty in the model — the applier
 * will fill it from the JAR's parsed {@link io.github.jarremapper.model.ClassInfo}
 * when applying the mapping. The {@link io.github.jarremapper.writer.TinyWriter}
 * will output what the model has; if no descriptor is known, it falls back to
 * the placeholder {@code Ljava/lang/Object;}.</p>
 */
public class SrgParser implements MappingParser {

    @Override
    public String formatId() { return "srg"; }
    @Override
    public String displayName() { return "SRG (MCP)"; }

    @Override
    public boolean matches(List<String> firstLines) {
        for (String l : firstLines) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            return t.startsWith("CL:") || t.startsWith("FD:") || t.startsWith("MD:");
        }
        return false;
    }

    @Override
    public MappingModel parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        MappingModel model = new MappingModel();
        model.setNamespaces("obfuscated", "named");

        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            // SRG entries are space-separated, but some flavors use tabs.
            String[] parts = t.split("\\s+");
            if (parts.length < 2) continue;
            switch (parts[0]) {
                case "CL:":
                    if (parts.length >= 3) {
                        model.put(parts[1], parts[2]);
                    }
                    break;
                case "FD:":
                    // obf/field orig/field
                    if (parts.length >= 3) {
                        String[] obf = splitClass(parts[1]);
                        String[] orig = splitClass(parts[2]);
                        // Field descriptor unknown — placeholder; the applier will fix.
                        model.putField(obf[0], orig[0], obf[1], "I", orig[1]);
                    }
                    break;
                case "MD:":
                    // obf/method desc orig/method desc
                    if (parts.length >= 5) {
                        String[] obf = splitClass(parts[1]);
                        String obfDesc = parts[2];
                        String[] orig = splitClass(parts[3]);
                        String origDesc = parts[4];
                        model.putMethod(obf[0], orig[0], obf[1], obfDesc, orig[1]);
                        // Note: origDesc dropped — the model uses obf descriptors as the key.
                        _ignored(origDesc);
                    }
                    break;
                default:
                    // ignore unknown prefixes (PR:, etc.)
            }
        }
        return model;
    }

    /** Splits a "className/memberName" token into [className, memberName]. */
    private static String[] splitClass(String qualified) {
        int idx = qualified.lastIndexOf('/');
        if (idx < 0) {
            return new String[] { qualified, qualified };
        }
        return new String[] {
                qualified.substring(0, idx),
                qualified.substring(idx + 1)
        };
    }

    private static void _ignored(String s) { /* no-op, kept for clarity */ }
}
