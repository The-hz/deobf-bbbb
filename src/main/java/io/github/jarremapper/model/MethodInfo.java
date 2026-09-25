package io.github.jarremapper.model;

/**
 * Method extracted from a class via ASM.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code key}  — name + descriptor, used as a stable identity for matching.</li>
 *   <li>{@code access} — ASM access flags (public/private/static/synchronized/...).</li>
   * <li>{@code insnFingerprint} — a 32-bit hash of the instruction sequence
 *       (opcode + important operands) — see
 *       {@link io.github.jarremapper.matcher.BytecodeStructureHash}.</li>
 *   <li>{@code maxStack} / {@code maxLocals} — useful as cheap secondary signals.</li>
 * </ul>
 */
public class MethodInfo {

    private final MemberKey key;
    private final int access;
    private final int insnFingerprint;
    private final int maxStack;
    private final int maxLocals;
    private final int insnCount;

    public MethodInfo(MemberKey key, int access, int insnFingerprint,
                      int maxStack, int maxLocals, int insnCount) {
        this.key = key;
        this.access = access;
        this.insnFingerprint = insnFingerprint;
        this.maxStack = maxStack;
        this.maxLocals = maxLocals;
        this.insnCount = insnCount;
    }

    public MemberKey key()             { return key; }
    public String name()               { return key.name(); }
    public String descriptor()         { return key.descriptor(); }
    public int    access()             { return access; }
    public int    insnFingerprint()    { return insnFingerprint; }
    public int    maxStack()           { return maxStack; }
    public int    maxLocals()          { return maxLocals; }
    public int    insnCount()          { return insnCount; }

    @Override
    public String toString() {
        return "Method[" + key + " / acc=0x" + Integer.toHexString(access) +
                " / fp=0x" + Integer.toHexString(insnFingerprint) +
                " / #insn=" + insnCount + "]";
    }
}
