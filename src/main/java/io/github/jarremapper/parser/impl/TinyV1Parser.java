package io.github.jarremapper.parser.impl;

import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.parser.MappingParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tiny v1 mapping parser. File layout:
 *
 * <pre>
 * tiny	1	obf-ns	named-ns
 * obf-class	named-class
 * 	obf-method	named-method	method-desc
 * 	obf-field	named-field	field-desc
 * </pre>
 */
public class TinyV1Parser implements MappingParser {

    @Override
    public String formatId() { return "tiny-v1"; }
    @Override
    public String displayName() { return "Tiny v1"; }

    @Override
    public boolean matches(List<String> firstLines) {
        if (firstLines.isEmpty()) return false;
        return firstLines.get(0).startsWith("tiny\t1\t") ||
               firstLines.get(0).startsWith("tiny 1 ");
    }

    @Override
    public MappingModel parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return new MappingModel();
        MappingModel model = new MappingModel();
        String[] header = lines.get(0).split("\t");
        if (header.length >= 3) {
            model.setNamespaces(header[1], header.length >= 4 ? header[2] : "named");
        }

        String currentObf = null;
        String currentOrig = null;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t");
            if (!line.startsWith("\t")) {
                // class line: obf, named
                if (parts.length >= 2) {
                    currentObf = parts[0];
                    currentOrig = parts[1];
                    model.put(currentObf, currentOrig);
                }
            } else {
                // member line: "", obf, named, desc
                if (currentObf != null && parts.length >= 4) {
                    String obfName = parts[1];
                    String origName = parts[2];
                    String desc = parts[3];
                    if (desc.startsWith("(")) {
                        model.putMethod(currentObf, currentOrig, obfName, desc, origName);
                    } else {
                        model.putField(currentObf, currentOrig, obfName, desc, origName);
                    }
                } else if (currentObf != null && parts.length >= 3) {
                    // No descriptor — assume method with empty descriptor.
                    model.putMethod(currentObf, currentOrig,
                            parts[1], "()V", parts[2]);
                }
            }
        }
        return model;
    }
}
