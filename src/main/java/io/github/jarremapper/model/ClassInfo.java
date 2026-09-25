package io.github.jarremapper.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight representation of a single class parsed out of a JAR via ASM.
 *
 * <p>This is NOT a fully materialized class tree — it deliberately keeps only
 * what the similarity matcher needs:
 * <ul>
 *   <li>the obfuscated internal name (e.g. {@code aaa/bbb}),</li>
 *   <li>its access flags,</li>
 *   <li>its super-class and implemented interfaces (these survive obfuscation
 *       poorly in real obfuscators, so they're valuable fingerprints),</li>
 *   <li>the list of methods with descriptor + access + a hashed "instruction
 *       signature" — see {@link MethodInfo#insnFingerprint()},</li>
 *   <li>the list of fields with descriptor + access.</li>
 * </ul>
 */
public class ClassInfo {

    private final String internalName;
    private int access;
    private String superName;
    private String[] interfaces;
    private final Map<MemberKey, MethodInfo> methods = new LinkedHashMap<>();
    private final Map<MemberKey, FieldInfo>  fields  = new LinkedHashMap<>();
    /** Optional: the original (named) class name if a mapping was applied during parsing. */
    private String originalName;

    public ClassInfo(String internalName) {
        this.internalName = internalName;
    }

    public String internalName()  { return internalName; }
    public String originalName()  { return originalName; }
    public int     access()       { return access; }
    public String  superName()    { return superName; }
    public String[] interfaces()  { return interfaces; }
    public Map<MemberKey, MethodInfo> methods() { return methods; }
    public Map<MemberKey, FieldInfo>  fields()  { return fields; }

    public void setAccess(int a)         { this.access = a; }
    public void setSuperName(String s)   { this.superName = s; }
    public void setInterfaces(String[] i) { this.interfaces = i; }
    public void setOriginalName(String n){ this.originalName = n; }

    public void addMethod(MethodInfo m) { methods.put(m.key(), m); }
    public void addField(FieldInfo f)   { fields.put(f.key(), f); }

    /** Number of methods (including static &lt;clinit&gt; and instance &lt;init&gt;). */
    public int methodCount() { return methods.size(); }

    /** Number of fields. */
    public int fieldCount()  { return fields.size(); }

    /** Pretty print, mainly for debugging. */
    @Override
    public String toString() {
        return "ClassInfo{" + internalName +
                (originalName != null ? " => " + originalName : "") +
                ", methods=" + methodCount() +
                ", fields=" + fieldCount() +
                '}';
    }
}
