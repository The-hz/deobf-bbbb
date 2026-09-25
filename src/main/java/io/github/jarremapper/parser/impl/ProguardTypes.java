package io.github.jarremapper.parser.impl;

/**
 * Helper for converting Mojang (proguard) source-level type names to JVM
 * descriptors.
 *
 * <p>Proguard uses Java source names like {@code int}, {@code java.lang.String},
 * {@code java.util.Map&lt;java.lang.String, java.lang.Integer&gt;[]}, etc.
 * We translate these to JVM descriptors {@code I}, {@code Ljava/lang/String;},
 * {@code [Ljava/util/Map;}, etc.</p>
 *
 * <p>Generic type info is dropped (it doesn't affect the bytecode descriptor
 * since erasure makes it identical to the raw type).</p>
 */
final class ProguardTypes {

    private ProguardTypes() {}

    /**
     * Convert a return type name + parameter list like
     * {@code ("java.lang.String", "(int,byte[],java.util.Map)")} to a method
     * descriptor {@code "(IBLjava/util/Map;)Ljava/lang/String;"}.
     */
    static String toMethodDescriptor(String retType, String params) {
        // params looks like "(int,java.lang.String,byte[])"
        String inner = params;
        if (inner.startsWith("(")) inner = inner.substring(1);
        if (inner.endsWith(")")) inner = inner.substring(0, inner.length() - 1);
        // Handle "<" generic angle brackets: strip everything from first '<' to matching '>'.
        inner = stripGenerics(inner);
        String[] argList = inner.isEmpty() ? new String[0] : inner.split(",");
        StringBuilder sb = new StringBuilder("(");
        for (String a : argList) {
            String t = a.trim();
            if (t.isEmpty()) continue;
            sb.append(toFieldDescriptor(t));
        }
        sb.append(')').append(toFieldDescriptor(retType));
        return sb.toString();
    }

    /**
     * Convert a single source-level type name to its JVM field descriptor.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code int} → {@code I}</li>
     *   <li>{@code java.lang.String} → {@code Ljava/lang/String;}</li>
     *   <li>{@code byte[]} → {@code [B}</li>
     *   <li>{@code java.util.Map&lt;java.lang.String,java.lang.Integer&gt;[]} → {@code [Ljava/util/Map;}</li>
     * </ul>
     */
    static String toFieldDescriptor(String type) {
        // Strip generic info first.
        String t = stripGenerics(type).trim();
        if (t.isEmpty()) return "Ljava/lang/Object;";

        // Count trailing "[]" array markers.
        int arrayDepth = 0;
        while (t.endsWith("[]")) {
            arrayDepth++;
            t = t.substring(0, t.length() - 2).trim();
        }

        String base;
        switch (t) {
            case "int":     base = "I"; break;
            case "long":    base = "J"; break;
            case "short":   base = "S"; break;
            case "byte":    base = "B"; break;
            case "char":    base = "C"; break;
            case "boolean": base = "Z"; break;
            case "float":   base = "F"; break;
            case "double":  base = "D"; break;
            case "void":    base = "V"; break;
            default:
                // Class type — convert '.' to '/' for internal form.
                String internal = t.replace('.', '/');
                base = "L" + internal + ";";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arrayDepth; i++) sb.append('[');
        sb.append(base);
        return sb.toString();
    }

    /** Remove the {@code <...>} generic section from a type name. */
    private static String stripGenerics(String s) {
        int i = s.indexOf('<');
        if (i < 0) return s;
        int j = s.lastIndexOf('>');
        if (j < 0 || j <= i) return s.substring(0, i);
        // Combine prefix and suffix — drop everything between < and >.
        return s.substring(0, i) + s.substring(j + 1);
    }
}
