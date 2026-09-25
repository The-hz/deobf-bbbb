package io.github.jarremapper.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of a complete (or partially-complete) obfuscation mapping.
 *
 * <p>For each obfuscated class name we store:
 * <ul>
 *   <li>the original (deobfuscated / named) class name,</li>
 *   <li>a map of method members (key = obf name + desc, value = original name),</li>
 *   <li>a map of field members  (key = obf name + desc, value = original name).</li>
 * </ul>
 *
 * <p>An identity mapping (aaa → aaa) is represented by storing the same value for
 * both obf and orig sides, marked with {@link ClassEntry#identity()} so the writer
 * can emit the entry verbatim.</p>
 */
public class MappingModel {

    public static final class ClassEntry {
        private final String obfName;
        private String origName;
        private final Map<MemberKey, String> methods = new LinkedHashMap<>();
        private final Map<MemberKey, String> fields  = new LinkedHashMap<>();
        /** True if this entry was auto-created as an identity (skip) mapping. */
        private boolean identity;

        public ClassEntry(String obfName, String origName) {
            this.obfName = obfName;
            this.origName = origName;
        }

        public String obfName()   { return obfName; }
        public String origName()  { return origName; }
        public Map<MemberKey, String> methods() { return methods; }
        public Map<MemberKey, String> fields()  { return fields; }
        public boolean identity() { return identity; }

        public void setOrigName(String origName) { this.origName = origName; }
        public void setIdentity(boolean v)       { this.identity = v; }

        /** Helper: mark this whole class as an identity mapping. */
        public void markIdentity() {
            this.identity = true;
            this.origName = obfName;
        }
    }

    /** Insertion order = output order (Tiny v2 writes them as encountered). */
    private final Map<String, ClassEntry> classes = new LinkedHashMap<>();

    /** The two column namespaces for tiny v2, e.g. "obfuscated" and "named". */
    private String obfNamespace = "obfuscated";
    private String namedNamespace = "named";

    /* ----- accessors ----- */

    public Map<String, ClassEntry> classes() { return classes; }

    public String obfNamespace()   { return obfNamespace; }
    public String namedNamespace() { return namedNamespace; }

    public void setNamespaces(String obf, String named) {
        this.obfNamespace = obf;
        this.namedNamespace = named;
    }

    public ClassEntry getOrCreate(String obfName) {
        return classes.computeIfAbsent(obfName, k -> new ClassEntry(k, k));
    }

    public ClassEntry get(String obfName) {
        return classes.get(obfName);
    }

    public ClassEntry put(String obfName, String origName) {
        ClassEntry e = new ClassEntry(obfName, origName);
        classes.put(obfName, e);
        return e;
    }

    /** Record a method mapping on a class, creating the class entry if needed. */
    public void putMethod(String obfClass, String origClass,
                          String obfName, String desc, String origName) {
        ClassEntry ce = getOrCreate(obfClass);
        if (origClass != null) {
            ce.setOrigName(origClass);
        }
        ce.methods().put(MemberKey.of(obfName, desc), origName);
    }

    /** Record a field mapping on a class. */
    public void putField(String obfClass, String origClass,
                         String obfName, String desc, String origName) {
        ClassEntry ce = getOrCreate(obfClass);
        if (origClass != null) {
            ce.setOrigName(origClass);
        }
        ce.fields().put(MemberKey.of(obfName, desc), origName);
    }

    /** Create an identity class entry (aaa → aaa) and mark it. */
    public ClassEntry markIdentity(String obfName) {
        ClassEntry e = getOrCreate(obfName);
        e.markIdentity();
        return e;
    }

    /** Snapshot of all class entries in insertion order. */
    public List<ClassEntry> entries() {
        return new ArrayList<>(classes.values());
    }

    /** Total class entries (including identity entries). */
    public int size() {
        return classes.size();
    }

    /** Lookup the original name of a class, falling back to the obf name itself. */
    public String mapClass(String obfName) {
        ClassEntry e = classes.get(obfName);
        return e == null ? obfName : e.origName();
    }

    /** Lookup the original method name on a class. */
    public String mapMethod(String obfClass, String name, String desc) {
        ClassEntry e = classes.get(obfClass);
        if (e == null) return name;
        String mapped = e.methods().get(MemberKey.of(name, desc));
        if (mapped != null) return mapped;
        // Fallback: same name on this class, ignoring descriptor. Used when
        // the original mapping format (e.g. SRG) stored no descriptor.
        String fallback = null;
        int hits = 0;
        for (Map.Entry<MemberKey, String> me : e.methods().entrySet()) {
            if (me.getKey().name().equals(name)) {
                fallback = me.getValue();
                hits++;
            }
        }
        // Only safe to use the fallback if there's exactly one match — otherwise
        // overload ambiguity forces us to bail out and keep the obf name.
        return hits == 1 ? fallback : name;
    }

    /** Lookup the original field name on a class. */
    public String mapField(String obfClass, String name, String desc) {
        ClassEntry e = classes.get(obfClass);
        if (e == null) return name;
        String mapped = e.fields().get(MemberKey.of(name, desc));
        if (mapped != null) return mapped;
        // Fallback: same field name on this class, ignoring descriptor.
        // Safe because the JVM forbids two fields with the same name on the
        // same class (no field overloading in Java).
        for (Map.Entry<MemberKey, String> fe : e.fields().entrySet()) {
            if (fe.getKey().name().equals(name)) {
                return fe.getValue();
            }
        }
        return name;
    }
}
