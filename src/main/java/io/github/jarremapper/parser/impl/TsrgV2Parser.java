package io.github.jarremapper.parser.impl;

import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.parser.MappingParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * TSRG v2 parser (Mojang's modern mapping format).
 *
 * <p>Layout (tab-separated):
 * <pre>
 * tsrg2        2       obf-ns  named-ns
 * c    aaa     com/example/Foo
 *      m       a       0       doStuff ()V
 *      f       a       0       value   I
 * c    aab     com/example/Bar
 * </pre>
 *
 * <p>The integer column after the obf name is a flag (1 = marked with side
 * annotations etc.); we skip it. The trailing descriptor column for methods
 * is the method descriptor ({@code ()V}), for fields it is the field type
 * descriptor ({@code I}, {@code Ljava/lang/String;}).</p>
 *
 * <p>Without the {@code c}/{@code m}/{@code f} markers (older flavours), the
 * parser falls back to "first row is a class" + "indented rows are members"
 * with the descriptor shape determining method vs field.</p>
 */
public class TsrgV2Parser implements MappingParser {

    @Override
    public String formatId() { return "tsrg-v2"; }
    @Override
    public String displayName() { return "TSRG v2"; }

    @Override
    public boolean matches(List<String> firstLines) {
        return !firstLines.isEmpty() &&
               (firstLines.get(0).startsWith("tsrg2\t") ||
                firstLines.get(0).startsWith("tsrg2 "));
    }

    @Override
    public MappingModel parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !matches(List.of(lines.get(0)))) {
            throw new IllegalArgumentException("Not a tsrg v2 file: " + file);
        }
        MappingModel model = new MappingModel();
        String[] header = lines.get(0).split("\t");
        if (header.length >= 3) {
            model.setNamespaces(header[1], header.length >= 3 ? header[2] : "named");
        } else {
            model.setNamespaces("obfuscated", "named");
        }

        String currentObf = null;
        String currentOrig = null;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t");
            if (!line.startsWith("\t")) {
                // Class line: ["c", obf, orig] or [obf, orig]
                int o = parts[0].equals("c") ? 1 : 0;
                if (parts.length >= o + 2) {
                    currentObf = parts[o];
                    currentOrig = parts[o + 1];
                    model.put(currentObf, currentOrig);
                }
            } else {
                // member line: ["", "m", obf-name, marker?, orig-name, desc?]
                // or ["", obf-name, orig-name, desc?]
                if (currentObf == null || parts.length < 3) continue;
                int idx = 1;
                String marker = parts[idx];
                boolean hasMarker = "m".equals(marker) || "f".equals(marker);
                if (hasMarker) idx++;
                if (parts.length < idx + 2) continue;
                String obfName = parts[idx];
                String next  = parts[idx + 1];
                String origName;
                String desc;
                // Detect descriptor position by looking for "(" or a type descriptor prefix.
                if (parts.length >= idx + 3 && looksLikeDesc(parts[idx + 2])) {
                    // obf-name, <marker-int>, orig-name, desc
                    origName = next;
                    desc = parts[idx + 2];
                } else if (parts.length >= idx + 3 && looksLikeDesc(next)) {
                    // obf-name, orig-name(?), desc — less common, fallback
                    origName = parts[idx + 2];
                    desc = next;
                } else {
                    origName = next;
                    desc = descFallbackFor(hasMarker ? marker : null);
                }
                if (hasMarker) {
                    if ("m".equals(marker)) {
                        model.putMethod(currentObf, currentOrig, obfName, desc, origName);
                    } else {
                        model.putField(currentObf, currentOrig, obfName, desc, origName);
                    }
                } else {
                    if (desc.startsWith("(")) {
                        model.putMethod(currentObf, currentOrig, obfName, desc, origName);
                    } else {
                        model.putField(currentObf, currentOrig, obfName, desc, origName);
                    }
                }
            }
        }
        return model;
    }

    private static boolean looksLikeDesc(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(0);
        if (c == '(') return true;
        if (c == '[' || c == 'L') return true;
        return s.length() == 1 && "IJSBCZFDV".indexOf(c) >= 0;
    }

    private static String descFallbackFor(String marker) {
        return "m".equals(marker) ? "()V" : "I";
    }
}
