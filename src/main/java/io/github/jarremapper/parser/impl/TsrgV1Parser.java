package io.github.jarremapper.parser.impl;

import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.parser.MappingParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * TSRG v1 parser (legacy MCP/Bukkit-style mappings).
 *
 * <p>Layout (tab-separated, no header):
 * <pre>
 * obf-class	named-class
 * 	obf-method	named-method	method-desc
 * 	obf-field	named-field	field-desc
 * </pre>
 *
 * <p>Member rows are distinguished from class rows by the leading tab. The
 * shape of the descriptor column ({@code (} prefix → method, else → field)
 * determines whether the row is a method or a field.</p>
 */
public class TsrgV1Parser implements MappingParser {

    @Override
    public String formatId() { return "tsrg-v1"; }
    @Override
    public String displayName() { return "TSRG v1"; }

    @Override
    public boolean matches(List<String> firstLines) {
        if (firstLines.isEmpty()) return false;
        String first = firstLines.get(0);
        // tsrg v1 has no header — it begins with a class line that is NOT
        // "tsrg2 ..." and is not a srg "CL: ..." line. We also accept "tsrg " (legacy marker).
        if (first.startsWith("tsrg2\t") || first.startsWith("tsrg2 ")) return false;
        if (first.startsWith("tiny\t") || first.startsWith("tiny ")) return false;
        if (first.startsWith("CL:") || first.startsWith("MD:") || first.startsWith("FD:")) return false;
        if (first.startsWith("#")) return false;
        // The first line must contain a tab (separating obf from named class)
        if (!first.contains("\t")) return false;
        // And the second line (if any) must start with a tab, indicating a member row.
        if (firstLines.size() >= 2) {
            return firstLines.get(1).startsWith("\t");
        }
        return true;
    }

    @Override
    public MappingModel parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        MappingModel model = new MappingModel();
        model.setNamespaces("obfuscated", "named");

        String currentObf = null;
        String currentOrig = null;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t");
            if (!line.startsWith("\t")) {
                if (parts.length >= 2) {
                    currentObf = parts[0];
                    currentOrig = parts[1];
                    model.put(currentObf, currentOrig);
                }
            } else if (currentObf != null && parts.length >= 3) {
                String obfName = parts[1];
                String origName = parts[2];
                String desc = parts.length >= 4 ? parts[3] : "()V";
                if (desc.startsWith("(")) {
                    model.putMethod(currentObf, currentOrig, obfName, desc, origName);
                } else {
                    model.putField(currentObf, currentOrig, obfName, desc, origName);
                }
            }
        }
        return model;
    }
}
