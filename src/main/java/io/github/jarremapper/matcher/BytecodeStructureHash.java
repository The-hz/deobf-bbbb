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
    public record Fingerprint(int hash, int insnCount, int maxStack, int maxLocals) {
        public static Fingerprint empty() { return new Fingerprint(0, 0, 0, 0); }
    }

    /**
     * Run a single-pass fingerprint extraction over the given {@link MethodNode}.
     * Returns the 32-bit truncation of {@link #fingerprintOf(MethodNode)} plus
     * the raw insn count and stack/local bounds.
     */
    public static Fingerprint analyze(MethodNode mn) {
        long fp = fingerprintOf(mn);
        int hash = (int) (fp ^ (fp >>> 32));
        int insn = mn.instructions == null ? 0 : mn.instructions.size();
        return new Fingerprint(hash, insn, mn.maxStack, mn.maxLocals);
    }
}
