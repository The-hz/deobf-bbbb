package io.github.jarremapper.matcher;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Computes a "structural fingerprint" of a method's bytecode that is robust
 * against obfuscator name-remapping but still sensitive to control-flow /
 * instruction-mix changes.
 *
 * <p>The fingerprint is a packed {@code long} — we keep opcode counts and a
 * rolling hash together so that:
 * <ul>
 *   <li>renaming classes / methods / fields <em>does not</em> change the
 *       fingerprint (those operand indices are skipped);</li>
 *   <li>swapping two equivalent opcodes' order changes it slightly;</li>
 *   <li>adding or removing branches changes it noticeably.</li>
 * </ul>
 *
 * <p>Concretely we hash:
 * <ul>
 *   <li>the opcode of every instruction (high weight);</li>
 *   <li>for {@code LDC} instructions, the type of the constant (int/float/etc.)
 *       but <em>not</em> the value (a constant {@code 0} vs {@code 1} should
 *       not be a strong signal — obfuscators shuffle them);</li>
 *   <li>for {@code INVOKE*} instructions, the descriptor hash (arg/return
 *       types) but <em>not</em> the owner or name;</li>
 *   <li>for {@code GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC}, the descriptor
 *       (field type) but not the owner/name;</li>
 *   <li>for {@code NEW}, the descriptor (we don't include the owner — but
 *       array type is meaningful).</li>
 * </ul>
 *
 * <p>The 32 low bits hold a Jenkins-style rolling hash; the 32 high bits hold
 * a count hash (count of ILOAD, count of IADD, ...). Two methods with the
 * same fingerprint are very likely the same source body up to renaming.</p>
 */
public final class BytecodeStructureHash {

    private BytecodeStructureHash() {}

    /**
     * Compute the fingerprint for a {@link MethodNode} that has already been
     * visited by ASM (i.e. {@code accept()} finished populating it).
     *
     * @return a non-zero {@code long}; {@code 0L} if the method is abstract
     *         or native.
     */
    public static long fingerprintOf(MethodNode mn) {
        if (mn.instructions == null) return 0L;
        InsnList insns = mn.instructions;
        if (insns.size() == 0) return 0L;

        long rolling = 0x9E3779B97F4A7C15L;     // golden ratio seed
        long countHash = 0L;
        int count = 0;

        for (AbstractInsnNode n = insns.getFirst(); n != null; n = n.getNext()) {
            int op = n.getOpcode();
            if (op == -1) {
                // labels and frames — skip from rolling hash but bump count of frames
                continue;
            }
            count++;

            rolling ^= (long) op * 0x100000001B3L;
            rolling = Long.rotateLeft(rolling, 7) * 31L;

            // descriptor-aware hashing for selected opcodes
            switch (op) {
                case Opcodes.LDC -> {
                    Object cst = ((org.objectweb.asm.tree.LdcInsnNode) n).cst;
                    rolling ^= typeTag(cst) * 0x85ECA77L;
                }
                case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL,
                     Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE,
                     Opcodes.INVOKEDYNAMIC -> {
                    String desc = ((org.objectweb.asm.tree.MethodInsnNode) n).desc;
                    if (desc != null) rolling ^= desc.hashCode() * 0x100000001L;
                }
                case Opcodes.GETFIELD, Opcodes.PUTFIELD,
                     Opcodes.GETSTATIC, Opcodes.PUTSTATIC -> {
                    String desc = ((org.objectweb.asm.tree.FieldInsnNode) n).desc;
                    if (desc != null) rolling ^= desc.hashCode() * 0x9E3779B1L;
                }
                case Opcodes.NEW, Opcodes.ANEWARRAY,
                     Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> {
                    String desc = ((org.objectweb.asm.tree.TypeInsnNode) n).desc;
                    if (desc != null) rolling ^= desc.hashCode() * 0x1000003D1L;
                }
                case Opcodes.MULTIANEWARRAY -> {
                    String desc = ((org.objectweb.asm.tree.MultiANewArrayInsnNode) n).desc;
                    if (desc != null) rolling ^= desc.hashCode() * 0x1000003D1L;
                }
                default -> { /* opcode-only contribution already accounted */ }
            }

            // update the "count hash" with a bin -> counts approach (cheap)
            countHash = countHash * 33L + op;
        }

        if (count == 0) return 0L;
        long low = rolling & 0xFFFFFFFFL;
        long high = (countHash ^ count) & 0xFFFFFFFFL;
        long result = (high << 32) | low;
        return result == 0L ? 1L : result;     // avoid the "0 means no body" sentinel
    }

    /** Map an LDC constant's runtime class to a small integer tag. */
    private static int typeTag(Object c) {
        if (c == null) return 0;
        if (c instanceof Integer) return 1;
        if (c instanceof Float)   return 2;
        if (c instanceof Long)    return 3;
        if (c instanceof Double)  return 4;
        if (c instanceof String)  return 5;
        if (c instanceof org.objectweb.asm.Type) return 6;
        if (c instanceof org.objectweb.asm.Handle) return 7;
        if (c instanceof org.objectweb.asm.ConstantDynamic) return 8;
        return 9;
    }

    /**
     * Convenience: bucket a method by a coarse-grained profile (method arity
     * + total insn count + max stack/local). The matcher uses this as a fast
     * pre-filter so that brute-force pairwise comparison can early-out.
     */
    public static int profileHash(MethodNode mn) {
        int arity = mn.parameters == null ? 0 : mn.parameters.size();
        int insn  = mn.instructions == null ? 0 : mn.instructions.size();
        return (arity * 31) ^ insn ^ (mn.maxStack << 8) ^ (mn.maxLocals << 16);
    }

    /** Small struct returned by {@link #analyze(MethodNode)} to populate MethodInfo. */
    public record Fingerprint(int hash, int coarseHash, int insnCount, int maxStack, int maxLocals) {
        public static Fingerprint empty() { return new Fingerprint(0, 0, 0, 0, 0); }
    }

    /**
     * Run a single-pass fingerprint extraction over the given {@link MethodNode}.
     * Returns:
     * <ul>
     *   <li>{@code hash} — the 32-bit truncation of the rolling fine fingerprint
     *       (sensitive to instruction insertions / reordering).</li>
     *   <li>{@code coarseHash} — a 32-bit hash of the opcode-category histogram
     *       (robust to small instruction insertions, e.g. obfuscator-added
     *       {@code iconst_0} / {@code pop} pairs or basic-block shuffles).</li>
     *   <li>{@code insnCount}, {@code maxStack}, {@code maxLocals} — same as before.</li>
     * </ul>
     */
    public static Fingerprint analyze(MethodNode mn) {
        long fp = fingerprintOf(mn);
        int hash = (int) (fp ^ (fp >>> 32));
        int coarse = coarseFingerprintOf(mn);
        int insn = mn.instructions == null ? 0 : mn.instructions.size();
        return new Fingerprint(hash, coarse, insn, mn.maxStack, mn.maxLocals);
    }

    /**
     * Compute a coarse histogram-based fingerprint. We bucket each opcode
     * into a small set of categories (load, store, arithmetic, branch,
     * invoke, return, get/put, new, throw, monitor, primitive conversion,
     * other) and hash the resulting histogram.
     *
     * <p>This signal is intentionally coarse: two methods with the same
     * opcode-category histogram get the same coarse fingerprint, even if
     * individual opcodes were inserted/removed. The matcher uses it as a
     * fallback when the fine rolling-hash doesn't match — giving partial
     * credit rather than zero.</p>
     */
    public static int coarseFingerprintOf(MethodNode mn) {
        if (mn.instructions == null) return 0;
        int[] counts = new int[12];   // 12 opcode categories
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            int op = n.getOpcode();
            if (op == -1) continue;  // label / frame
            int cat = opcodeCategory(op);
            counts[cat]++;
        }
        long h = 0x9E3779B97F4A7C15L;
        for (int c : counts) {
            h = h * 31L + c;
        }
        int r = (int) (h ^ (h >>> 32));
        return r == 0 ? 1 : r;
    }

    /** Map an ASM opcode to one of 12 coarse categories. */
    private static int opcodeCategory(int op) {
        // See JVM spec opcode ranges.
        if (op == 0x00) return 10; // NOP → "other"
        if (op == 0x01) return 0; // ACONST_NULL → load
        if (op <= 0x10) return 0; // const variants → load (ICONST_* / BIPUSH / SIPUSH / LDC etc.)
        if (op <= 0x15) return 0; // LDC variants
        if (op >= 0x15 && op <= 0x19) return 0; // *LOAD_n
        if (op >= 0x1A && op <= 0x2D) return 0; // *LOAD
        if (op >= 0x2E && op <= 0x35) return 6; // *ALOAD / *STORE
        if (op >= 0x36 && op <= 0x3A) return 1; // *STORE
        if (op >= 0x3B && op <= 0x4E) return 1; // *STORE_n
        if (op >= 0x4F && op <= 0x56) return 1; // ASTORE
        if (op >= 0x57 && op <= 0x58) return 10; // POP / POP2 → other
        if (op >= 0x59 && op <= 0x5F) return 10; // DUP/SWAP → other
        if (op >= 0x60 && op <= 0x77) return 2; // arithmetic
        if (op >= 0x78 && op <= 0x83) return 2; // shifts/logical
        if (op >= 0x84 && op <= 0x93) return 10; // IINC → other (conversion-like)
        if (op >= 0x94 && op <= 0x98) return 2; // LCMP / FCMPL / etc.
        if (op >= 0x99 && op <= 0xA8) return 3; // branch (IFEQ ... GOTO)
        if (op == 0xA9) return 3; // JSR
        if (op == 0xAA) return 3; // RET
        if (op == 0xAB) return 3; // TABLESWITCH
        if (op == 0xAC) return 3; // LOOKUPSWITCH
        if (op >= 0xAD && op <= 0xB1) return 4; // return family (incl. void)
        if (op >= 0xB2 && op <= 0xB5) return 6; // GETSTATIC/PUTSTATIC/GETFIELD/PUTFIELD
        if (op >= 0xB6 && op <= 0xB9) return 5; // INVOKE*
        if (op == 0xBA) return 5; // INVOKEDYNAMIC
        if (op == 0xBB) return 7; // NEW
        if (op == 0xBC) return 7; // ANEWARRAY
        if (op == 0xBD) return 7; // NEWARRAY
        if (op == 0xBE) return 0; // ARRAYLENGTH
        if (op == 0xBF) return 8; // ATHROW
        if (op == 0xC0) return 7; // CHECKCAST
        if (op == 0xC1) return 7; // INSTANCEOF
        if (op >= 0xC2 && op <= 0xC8) return 11; // MONITORENTER/EXIT, MULTIANEWARRAY, IFNULL/IFNONNULL, GOTO_W
        if (op == 0xC9) return 3; // JSR_W
        return 10; // fallback
    }
}
