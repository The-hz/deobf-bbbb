package io.github.jarremapper.parser.impl;

import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.parser.MappingParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tiny v2 mapping parser.
 *
 * <p>File layout (tab-separated, blank-line-free):
 * <pre>
 * tiny 2       0       obfuscated      named
 * c    aaa     com/example/Foo
 *      m       a       (Ljava/lang/String;)V   doStuff
 *      m       b       ()I     getCount
 *      f       a       I       value
 * c    aab     com/example/Bar
 * </pre>
 *
 * <p>The {@code m}/{@code f} marker is optional in some flavours — when the
 * first non-tab character is alphabetic and there is no marker, we treat the
 * row as a member based on descriptor shape ({@code (} for method, anything
 * else for field).</p>
 */
public class TinyV2Parser implements MappingParser {

    @Override
    public String formatId() { return "tiny-v2"; }
    @Override
    public String displayName() { return "Tiny v2"; }

    @Override
    public boolean matches(List<String> firstLines) {
        if (firstLines.isEmpty()) return false;
        String head = firstLines.get(0);
        return head.startsWith("tiny\t2\t") || head.startsWith("tiny 2 ");
    }

    @Override
    public MappingModel parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !matches(List.of(lines.get(0)))) {
            throw new IllegalArgumentException("Not a Tiny v2 file: " + file);
        }
        MappingModel model = new MappingModel();
        // Header: tiny \t2\t<minor>\t<obf-ns>\t<named-ns>[...]
        String[] header = lines.get(0).split("\t");
        if (header.length >= 4) {
            model.setNamespaces(header[3], header.length >= 5 ? header[4] : "named");
        }

        String currentObf = null;
        String currentOrig = null;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) continue;
            // Convert possible space-separated variants to tab for uniform parsing
            String[] parts = line.split("\t");
            // Class line: "c", obf, orig [, ...]
            if (parts.length >= 3 && "c".equals(parts[0])) {
                currentObf = parts[1];
                currentOrig = parts[2];
                model.put(currentObf, currentOrig);
                continue;
            }
            // Member line: leading empty (tab prefix), marker m/f, obf-name, [obf-desc / extra], orig-name, [orig-desc]
            // parts[0] is empty when there's a leading tab.
            if (currentObf != null && parts.length >= 3 && parts[0].isEmpty()) {
                String marker = parts[1];
                if ("m".equals(marker)) {
                    // (parts[2]=obf-name, parts[3]=obf-desc, parts[4]=orig-name, parts[5]=orig-desc)
                    // Some flavours omit the obf-desc; in that case shift.
                    if (parts.length >= 5 && looksLikeDesc(parts[3])) {
                        model.putMethod(currentObf, currentOrig,
                                parts[2], parts[3], parts[4]);
                    } else if (parts.length >= 4) {
                        // obf-name, orig-name (descriptor unknown — leave blank)
                        model.putMethod(currentObf, currentOrig,
                                parts[2], "()V", parts[3]);
                    }
                } else if ("f".equals(marker)) {
                    if (parts.length >= 5 && looksLikeDesc(parts[3])) {
                        model.putField(currentObf, currentOrig,
                                parts[2], parts[3], parts[4]);
                    } else if (parts.length >= 4) {
                        model.putField(currentObf, currentOrig,
                                parts[2], "I", parts[3]);
                    }
                }
            }
        }
        return model;
    }

    private static boolean looksLikeDesc(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(0);
        // Method descriptor starts with '('.
        if (c == '(') return true;
        // Array or object type.
        if (c == '[' || c == 'L') return true;
        // Primitive type descriptors — single character only.
        return s.length() == 1 && "IJSBCZFDV".indexOf(c) >= 0;
    }
}
