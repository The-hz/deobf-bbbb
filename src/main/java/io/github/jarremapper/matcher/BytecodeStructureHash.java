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
     * into one of 11 categories (see {@link #opcodeCategory(int)}) and hash
     * the resulting histogram.
     *
     * <p>This signal is intentionally coarse: two methods with the same
     * opcode-category histogram get the same coarse fingerprint, even if
     * individual opcodes were inserted/removed. The matcher uses it as a
     * fallback when the fine rolling-hash doesn't match — giving partial
     * credit rather than zero.</p>
     */
    public static int coarseFingerprintOf(MethodNode mn) {
        if (mn.instructions == null) return 0;
        int[] counts = new int[11];   // 11 opcode categories, see opcodeCategory
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            int op = n.getOpcode();
            if (op == -1) continue;  // label / frame
            counts[opcodeCategory(op)]++;
        }
        long h = 0x9E3779B97F4A7C15L;
        for (int c : counts) {
            h = h * 31L + c;
        }
        int r = (int) (h ^ (h >>> 32));
        return r == 0 ? 1 : r;
    }

    /**
     * Map an ASM opcode to one of 11 coarse categories. The category set is:
     * <ul>
     *   <li>{@code 0} — load (constants + local loads + array loads + arraylength)</li>
     *   <li>{@code 1} — store (local stores + array stores)</li>
     *   <li>{@code 2} — arithmetic (add/sub/mul/div/rem/neg/shift/logical/conv/cmp/iinc)</li>
     *   <li>{@code 3} — branch (if* / goto / jsr / ret / switch / goto_w / jsr_w / ifnull / ifnonnull)</li>
     *   <li>{@code 4} — return</li>
     *   <li>{@code 5} — invoke (invoke* + invokedynamic)</li>
     *   <li>{@code 6} — field get/put (getstatic / putstatic / getfield / putfield)</li>
     *   <li>{@code 7} — new + type (new / newarray / anewarray / checkcast / instanceof)</li>
     *   <li>{@code 8} — throw (athrow)</li>
     *   <li>{@code 9} — monitor (monitorenter / monitorexit / multianewarray)</li>
     *   <li>{@code 10} — other (nop / pop / pop2 / dup* / swap / wide)</li>
     * </ul>
     *
     * <p>The table is exhaustive over the standard JVM opcode space
     * (0x00 .. 0xC9) plus a default case for any reserved opcodes (BREAKPOINT,
     * IMPDEP1, IMPDEP2) that round to {@code 10} (other).</p>
     */
    static int opcodeCategory(int op) {
        // Using a switch expression with explicit opcode constants from
        // Opcodes. ASM normalizes the JVM spec's "_0..3" short forms
        // (ILOAD_0..3, etc.), LDC_W, LDC2_W, GOTO_W, JSR_W, and WIDE
        // to their long forms (ILOAD, LDC, GOTO, JSR) when reading class
        // files — so AbstractInsnNode.getOpcode() only ever returns the
        // long-form constants. We list each explicitly; reserved opcodes
        // (BREAKPOINT=202, IMPDEP1=254, IMPDEP2=255) round to "other"
        // via the default branch.
        return switch (op) {
            // ----- 0: load (constants + local loads + array loads + arraylength) -----
            case Opcodes.ACONST_NULL,
                 Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1,
                 Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
                 Opcodes.LCONST_0, Opcodes.LCONST_1,
                 Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
                 Opcodes.DCONST_0, Opcodes.DCONST_1,
                 Opcodes.BIPUSH, Opcodes.SIPUSH,
                 Opcodes.LDC,
                 Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD,
                 Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                 Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
                 Opcodes.ARRAYLENGTH
                    -> 0;

            // ----- 1: store (local stores + array stores) -----
            case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE,
                 Opcodes.DSTORE, Opcodes.ASTORE,
                 Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                 Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE
                    -> 1;

            // ----- 2: arithmetic (add/sub/mul/div/rem/neg/shift/logical/conv/cmp/iinc) -----
            case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD,
                 Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB,
                 Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL,
                 Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV,
                 Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM,
                 Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG,
                 Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR,
                 Opcodes.IUSHR, Opcodes.LUSHR,
                 Opcodes.IAND, Opcodes.LAND,
                 Opcodes.IOR, Opcodes.LOR,
                 Opcodes.IXOR, Opcodes.LXOR,
                 Opcodes.IINC,
                 Opcodes.I2L, Opcodes.I2F, Opcodes.I2D,
                 Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                 Opcodes.F2I, Opcodes.F2L, Opcodes.F2D,
                 Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
                 Opcodes.I2B, Opcodes.I2C, Opcodes.I2S,
                 Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG,
                 Opcodes.DCMPL, Opcodes.DCMPG
                    -> 2;

            // ----- 3: branch -----
            case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE,
                 Opcodes.IFGT, Opcodes.IFLE,
                 Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
                 Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                 Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                 Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE,
                 Opcodes.IFNULL, Opcodes.IFNONNULL,
                 Opcodes.GOTO, Opcodes.JSR,
                 Opcodes.RET,
                 Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH
                    -> 3;

            // ----- 4: return -----
            case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN,
                 Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.RETURN
                    -> 4;

            // ----- 5: invoke -----
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL,
                 Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE,
                 Opcodes.INVOKEDYNAMIC
                    -> 5;

            // ----- 6: field get/put -----
            case Opcodes.GETSTATIC, Opcodes.PUTSTATIC,
                 Opcodes.GETFIELD, Opcodes.PUTFIELD
                    -> 6;

            // ----- 7: new + type ops -----
            case Opcodes.NEW, Opcodes.NEWARRAY, Opcodes.ANEWARRAY,
                 Opcodes.CHECKCAST, Opcodes.INSTANCEOF
                    -> 7;

            // ----- 8: throw -----
            case Opcodes.ATHROW -> 8;

            // ----- 9: monitor + multianewarray -----
            case Opcodes.MONITORENTER, Opcodes.MONITOREXIT,
                 Opcodes.MULTIANEWARRAY
                    -> 9;

            // ----- 10: other (stack manipulation + control) -----
            case Opcodes.NOP,
                 Opcodes.POP, Opcodes.POP2,
                 Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2,
                 Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2,
                 Opcodes.SWAP
                    -> 10;

            // Default: any reserved opcode (BREAKPOINT=202, IMPDEP1=254,
            // IMPDEP2=255, etc.) → "other".
            default -> 10;
        };
    }
}
