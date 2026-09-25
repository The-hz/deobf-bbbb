package io.github.jarremapper.parser.impl;

import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.parser.MappingParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mojang Proguard-format mapping parser.
 *
 * <p>File layout (note: lines are <em>named → obfuscated</em>, the opposite of
 * Tiny / TSRG, so we swap when storing in {@link MappingModel}):
 * <pre>
 * # comment line(s) at the top
 * com.example.Foo -> aaa:
 *     int value -> a
 *     void doStuff(java.lang.String) -> b
 *     1:3:void method2(int,int) -> c
 *     1:2:java.lang.String method3() -> d
 * </pre>
 *
 * <p>Member lines begin with whitespace. Class lines have no leading
 * whitespace and end with a colon. Inner class names are appended to the
 * outer name with {@code $} — we propagate that into the internal form
 * ({@code com/example/Foo$Bar}).</p>
 *
 * <p>Method descriptors are rebuilt from the source-level type names via
 * {@link ProguardTypes#toMethodDescriptor(String)}; field type names are
 * converted via {@link ProguardTypes#toFieldDescriptor(String)}.</p>
 */
public class MojangParser implements MappingParser {

    @Override
    public String formatId() { return "mojang"; }
    @Override
    public String displayName() { return "Mojang (proguard)"; }

    @Override
    public boolean matches(List<String> firstLines) {
        // Heuristic: contains at least one line with " -> " followed by a colon
        // at the end, OR a line that is "# mapping file" header.
        for (String l : firstLines) {
            if (l == null) continue;
            if (l.contains(" -> ") && l.trim().endsWith(":")) return true;
            if (l.startsWith("# mapping file")) return true;
        }
        return false;
    }

    @Override
    public MappingModel parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        MappingModel model = new MappingModel();
        model.setNamespaces("obfuscated", "named");

        // Stack of (named-class-stack) so we can support nested inner classes.
        List<String> outerNamedStack = new ArrayList<>();
        List<String> outerObfStack  = new ArrayList<>();

        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String line = rawLine;
            // Strip CR for CRLF inputs.
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isBlank()) continue;
            if (line.startsWith("#")) continue;

            boolean isMember = Character.isWhitespace(line.charAt(0));
            String trimmed = line.trim();

            if (!isMember) {
                if (!trimmed.endsWith(":")) continue;
                // Strip trailing ':'
                String head = trimmed.substring(0, trimmed.length() - 1).trim();
                int arrow = head.indexOf(" -> ");
                if (arrow < 0) continue;
                String named = head.substring(0, arrow).trim();
                String obf   = head.substring(arrow + 4).trim();

                // Pop stacks while the stack top is not the named's outer.
                // Inner class names appear as "Outer$Inner" — we split to
                // know the depth and rebuild the full path.
                int depth = countDepth(named);
                while (outerNamedStack.size() > depth - 1) {
                    outerNamedStack.remove(outerNamedStack.size() - 1);
                    outerObfStack.remove(outerObfStack.size() - 1);
                }
                outerNamedStack.add(named);
                outerObfStack.add(obf);

                // Build full paths.
                String fullNamed = joinNamedChain(outerNamedStack);
                String fullObf   = joinNamedChain(outerObfStack);
                // Proguard uses dotted package form; convert to internal form.
                String obfInternal = toInternal(fullObf);
                String origInternal = toInternal(fullNamed);
                model.put(obfInternal, origInternal);
            } else {
                // member row: [start:end:] type membername(args) -> obf
                // OR          type fieldname -> obf
                // Strip leading "n:m:" or "n:" line range prefix if present.
                String rest = stripLineRange(trimmed);
                int arrow = rest.indexOf(" -> ");
                if (arrow < 0) continue;
                String left = rest.substring(0, arrow).trim();
                String obf = rest.substring(arrow + 4).trim();
                if (left.isEmpty() || obf.isEmpty()) continue;

                if (outerNamedStack.isEmpty() || outerObfStack.isEmpty()) continue;
                String obfClassInternal   = toInternal(joinNamedChain(outerObfStack));
                String origClassInternal  = toInternal(joinNamedChain(outerNamedStack));

                int parenIdx = left.indexOf('(');
                if (parenIdx >= 0) {
                    // method: "rettype name(argtypes)"
                    int sp = left.lastIndexOf(' ', parenIdx);
                    String retType = sp < 0 ? "void" : left.substring(0, sp).trim();
                    String methodName = sp < 0 ? left.substring(0, parenIdx).trim()
                                               : left.substring(sp + 1, parenIdx).trim();
                    String args = left.substring(parenIdx);
                    String desc = ProguardTypes.toMethodDescriptor(retType, args);
                    model.putMethod(obfClassInternal, origClassInternal, obf, desc, methodName);
                } else {
                    // field: "type name"
                    int sp = left.lastIndexOf(' ');
                    if (sp < 0) continue;
                    String type = left.substring(0, sp).trim();
                    String fieldName = left.substring(sp + 1).trim();
                    String desc = ProguardTypes.toFieldDescriptor(type);
                    model.putField(obfClassInternal, origClassInternal, obf, desc, fieldName);
                }
            }
        }
        return model;
    }

    /** Count how many {@code $} separators are in the named-class path. */
    private static int countDepth(String named) {
        int depth = 1;
        for (int i = 0; i < named.length(); i++) {
            if (named.charAt(i) == '$') depth++;
        }
        return depth;
    }

    /** Join the named stack: the outermost is the package; inner classes are appended with {@code $}. */
    private static String joinNamedChain(List<String> chain) {
        // chain[0] is the top-level: "com.example.Foo"
        // chain[1] is an inner class: stored as "Inner" (just the simple name) OR as "Foo$Inner" — we be tolerant.
        if (chain.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(chain.get(0));
        for (int i = 1; i < chain.size(); i++) {
            String seg = chain.get(i);
            if (seg.startsWith(sb.toString())) {
                // It is fully qualified already.
                sb = new StringBuilder(seg);
            } else if (seg.contains(".")) {
                sb.append('$').append(seg.substring(seg.lastIndexOf('.') + 1));
            } else {
                sb.append('$').append(seg);
            }
        }
        return sb.toString();
    }

    /** Replace dots in the package portion with slashes for internal JVM form. */
    private static String toInternal(String dotted) {
        // Replace only package-separator dots; inner-class '$' is preserved as-is.
        // We can't trivially replace all dots because of class names like "java.util.Map$Entry"
        // but in proguard output, inner classes always use '$' as separator.
        return dotted.replace('.', '/');
    }

    /**
     * Strip a leading "{@code n:m:}" or "{@code n:}" line range prefix from
     * a member row, returning the rest.
     */
    private static String stripLineRange(String s) {
        int i = 0;
        int n = s.length();
        // Digit run
        while (i < n && Character.isDigit(s.charAt(i))) i++;
        if (i == 0 || i == n) return s;
        if (s.charAt(i) == ':') {
            i++;
            // Optionally a second digit run + ':' for start:end: form
            int j = i;
            while (j < n && Character.isDigit(s.charAt(j))) j++;
            if (j > i && j < n && s.charAt(j) == ':') {
                i = j + 1;
            }
            return s.substring(i).trim();
        }
        return s;
    }
}
