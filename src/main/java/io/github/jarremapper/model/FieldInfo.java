package io.github.jarremapper.model;

/**
 * Field extracted from a class via ASM.
 */
public class FieldInfo {

    private final MemberKey key;
    private final int access;
    /** A signature-like fingerprint for the field's initializer (if any). */
    private final int initFingerprint;

    public FieldInfo(MemberKey key, int access, int initFingerprint) {
        this.key = key;
        this.access = access;
        this.initFingerprint = initFingerprint;
    }

    public MemberKey key()          { return key; }
    public String name()            { return key.name(); }
    public String descriptor()      { return key.descriptor(); }
    public int    access()          { return access; }
    public int    initFingerprint() { return initFingerprint; }

    @Override
    public String toString() {
        return "Field[" + key + " / acc=0x" + Integer.toHexString(access) +
                " / fp=0x" + Integer.toHexString(initFingerprint) + "]";
    }
}
