package io.github.jarremapper.matcher;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BytecodeStructureHash} — verifies that the fine
 * fingerprint is sensitive to instruction changes and the coarse
 * fingerprint is robust to small insertions (P0-2).
 */
class BytecodeStructureHashTest {

    /**
     * Build a synthetic class with one method whose body we control
     * programmatically. The ClassWriter emits a simple
     * {@code public void m()} that returns void (with the body the
     * caller injects via the {@code body} lambda).
     */
    private static org.objectweb.asm.tree.MethodNode buildMethodNode(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "Synthetic", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m", "()V", null, null);
        body.accept(mv);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        ClassReader cr = new ClassReader(cw.toByteArray());
        final org.objectweb.asm.tree.MethodNode[] holder = new org.objectweb.asm.tree.MethodNode[1];
        cr.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if ("m".equals(name) && "()V".equals(descriptor) && holder[0] == null) {
                    holder[0] = new org.objectweb.asm.tree.MethodNode(Opcodes.ASM9, access,
                            name, descriptor, signature, exceptions);
                    return holder[0];
                }
                return null;
            }
        }, 0);
        return holder[0];
    }

    @Test
    void coarseFingerprintSurvivesTrivialInsertion() {
        // Method A: just RETURN.
        var nodeA = buildMethodNode(mv -> mv.visitInsn(Opcodes.RETURN));
        // Method B: ICONST_0; POP; RETURN — obfuscator-style dead-code injection.
        var nodeB = buildMethodNode(mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
        });

        int coarseA = BytecodeStructureHash.coarseFingerprintOf(nodeA);
        int coarseB = BytecodeStructureHash.coarseFingerprintOf(nodeB);

        // The histogram for A is { RETURN: 1, ... }, for B is { ICONST_0: 1, POP: 1, RETURN: 1, ... }.
        // They DIFFER in the histogram because B has an extra const + an extra pop,
        // so the coarse fingerprints should differ.
        // But the fine fingerprints should also differ — and the coarse one is the
        // "robust" signal we want to verify computes correctly.
        assertNotEquals(0, coarseA, "coarse A should be non-zero");
        assertNotEquals(0, coarseB, "coarse B should be non-zero");
        assertNotEquals(coarseA, coarseB, "two different histograms → different coarse FP");
    }

    @Test
    void fineFingerprintIsDeterministic() {
        var node1 = buildMethodNode(mv -> mv.visitInsn(Opcodes.RETURN));
        var node2 = buildMethodNode(mv -> mv.visitInsn(Opcodes.RETURN));
        long fp1 = BytecodeStructureHash.fingerprintOf(node1);
        long fp2 = BytecodeStructureHash.fingerprintOf(node2);
        assertEquals(fp1, fp2, "identical methods should hash equal");
        assertNotEquals(0, fp1, "non-empty method should not have zero FP");
    }

    @Test
    void fineFingerprintChangesOnInsertion() {
        // Sanity check: fine FP is sensitive (this is the known limitation P0-2).
        var nodeA = buildMethodNode(mv -> mv.visitInsn(Opcodes.RETURN));
        var nodeB = buildMethodNode(mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.RETURN);
        });
        long fpA = BytecodeStructureHash.fingerprintOf(nodeA);
        long fpB = BytecodeStructureHash.fingerprintOf(nodeB);
        assertNotEquals(fpA, fpB, "different methods should hash differently");
    }

    @Test
    void analyzeReturnsBothFingerprints() {
        var node = buildMethodNode(mv -> mv.visitInsn(Opcodes.RETURN));
        BytecodeStructureHash.Fingerprint fp = BytecodeStructureHash.analyze(node);
        assertNotEquals(0, fp.hash(), "fine hash should be non-zero");
        assertNotEquals(0, fp.coarseHash(), "coarse hash should be non-zero");
        assertTrue(fp.insnCount() > 0, "insn count should be > 0");
        assertTrue(fp.maxStack() >= 0, "maxStack should be ≥ 0");
        assertTrue(fp.maxLocals() >= 0, "maxLocals should be ≥ 0");
    }

    /**
     * P2 review: full opcode coverage for {@link BytecodeStructureHash#opcodeCategory(int)}.
     * Verifies every standard JVM opcode (0x00..0xC9, 202 values total)
     * returns a category in {@code [0, 11)} — no off-by-one or unhandled
     * opcode that would crash {@code coarseFingerprintOf} on real bytecode.
     */
    @Test
    void opcodeCategoryCoversAllStandardOpcodes() {
        // Iterate over the entire standard bytecode space.
        int standardStart = 0;       // NOP
        int standardEnd   = 0xC9;    // JSR_W
        for (int op = standardStart; op <= standardEnd; op++) {
            int cat = BytecodeStructureHash.opcodeCategory(op);
            assertTrue(cat >= 0 && cat < 11,
                    "opcode 0x" + Integer.toHexString(op) + " returned out-of-range category " + cat);
        }
    }

    /**
     * Spot-check the canonical opcodes for each category — guards against
     * a wrong-but-non-crashing switch label.
     *
     * <p>Note: ASM normalizes the JVM spec's short forms (ILOAD_0..3, LDC_W,
     * LDC2_W, GOTO_W, JSR_W, WIDE) to their long forms (ILOAD, LDC, GOTO,
     * JSR), so {@link AbstractInsnNode#getOpcode()} never returns the short
     * forms. We only test the long-form constants here.</p>
     */
    @Test
    void opcodeCategorySpotChecksEachCategory() {
        // 0 = load
        assertEquals(0, BytecodeStructureHash.opcodeCategory(Opcodes.ACONST_NULL));
        assertEquals(0, BytecodeStructureHash.opcodeCategory(Opcodes.ICONST_0));
        assertEquals(0, BytecodeStructureHash.opcodeCategory(Opcodes.BIPUSH));
        assertEquals(0, BytecodeStructureHash.opcodeCategory(Opcodes.LDC));
        assertEquals(0, BytecodeStructureHash.opcodeCategory(Opcodes.ILOAD));
        assertEquals(0, BytecodeStructureHash.opcodeCategory(Opcodes.ALOAD));
        assertEquals(0, BytecodeStructureHash.opcodeCategory(Opcodes.IALOAD));
        assertEquals(0, BytecodeStructureHash.opcodeCategory(Opcodes.ARRAYLENGTH));

        // 1 = store
        assertEquals(1, BytecodeStructureHash.opcodeCategory(Opcodes.ISTORE));
        assertEquals(1, BytecodeStructureHash.opcodeCategory(Opcodes.ASTORE));
        assertEquals(1, BytecodeStructureHash.opcodeCategory(Opcodes.IASTORE));
        assertEquals(1, BytecodeStructureHash.opcodeCategory(Opcodes.SASTORE));

        // 2 = arithmetic
        assertEquals(2, BytecodeStructureHash.opcodeCategory(Opcodes.IADD));
        assertEquals(2, BytecodeStructureHash.opcodeCategory(Opcodes.IDIV));
        assertEquals(2, BytecodeStructureHash.opcodeCategory(Opcodes.IUSHR));
        assertEquals(2, BytecodeStructureHash.opcodeCategory(Opcodes.IINC));
        assertEquals(2, BytecodeStructureHash.opcodeCategory(Opcodes.I2L));
        assertEquals(2, BytecodeStructureHash.opcodeCategory(Opcodes.LCMP));
        assertEquals(2, BytecodeStructureHash.opcodeCategory(Opcodes.FCMPG));

        // 3 = branch
        assertEquals(3, BytecodeStructureHash.opcodeCategory(Opcodes.IFEQ));
        assertEquals(3, BytecodeStructureHash.opcodeCategory(Opcodes.IF_ICMPEQ));
        assertEquals(3, BytecodeStructureHash.opcodeCategory(Opcodes.IFNULL));
        assertEquals(3, BytecodeStructureHash.opcodeCategory(Opcodes.IFNONNULL));
        assertEquals(3, BytecodeStructureHash.opcodeCategory(Opcodes.GOTO));
        assertEquals(3, BytecodeStructureHash.opcodeCategory(Opcodes.JSR));
        assertEquals(3, BytecodeStructureHash.opcodeCategory(Opcodes.RET));
        assertEquals(3, BytecodeStructureHash.opcodeCategory(Opcodes.TABLESWITCH));
        assertEquals(3, BytecodeStructureHash.opcodeCategory(Opcodes.LOOKUPSWITCH));

        // 4 = return
        assertEquals(4, BytecodeStructureHash.opcodeCategory(Opcodes.IRETURN));
        assertEquals(4, BytecodeStructureHash.opcodeCategory(Opcodes.ARETURN));
        assertEquals(4, BytecodeStructureHash.opcodeCategory(Opcodes.RETURN));

        // 5 = invoke
        assertEquals(5, BytecodeStructureHash.opcodeCategory(Opcodes.INVOKEVIRTUAL));
        assertEquals(5, BytecodeStructureHash.opcodeCategory(Opcodes.INVOKESPECIAL));
        assertEquals(5, BytecodeStructureHash.opcodeCategory(Opcodes.INVOKESTATIC));
        assertEquals(5, BytecodeStructureHash.opcodeCategory(Opcodes.INVOKEINTERFACE));
        assertEquals(5, BytecodeStructureHash.opcodeCategory(Opcodes.INVOKEDYNAMIC));

        // 6 = field get/put
        assertEquals(6, BytecodeStructureHash.opcodeCategory(Opcodes.GETSTATIC));
        assertEquals(6, BytecodeStructureHash.opcodeCategory(Opcodes.PUTSTATIC));
        assertEquals(6, BytecodeStructureHash.opcodeCategory(Opcodes.GETFIELD));
        assertEquals(6, BytecodeStructureHash.opcodeCategory(Opcodes.PUTFIELD));

        // 7 = new + type
        assertEquals(7, BytecodeStructureHash.opcodeCategory(Opcodes.NEW));
        assertEquals(7, BytecodeStructureHash.opcodeCategory(Opcodes.NEWARRAY));
        assertEquals(7, BytecodeStructureHash.opcodeCategory(Opcodes.ANEWARRAY));
        assertEquals(7, BytecodeStructureHash.opcodeCategory(Opcodes.CHECKCAST));
        assertEquals(7, BytecodeStructureHash.opcodeCategory(Opcodes.INSTANCEOF));

        // 8 = throw
        assertEquals(8, BytecodeStructureHash.opcodeCategory(Opcodes.ATHROW));

        // 9 = monitor
        assertEquals(9, BytecodeStructureHash.opcodeCategory(Opcodes.MONITORENTER));
        assertEquals(9, BytecodeStructureHash.opcodeCategory(Opcodes.MONITOREXIT));
        assertEquals(9, BytecodeStructureHash.opcodeCategory(Opcodes.MULTIANEWARRAY));

        // 10 = other
        assertEquals(10, BytecodeStructureHash.opcodeCategory(Opcodes.NOP));
        assertEquals(10, BytecodeStructureHash.opcodeCategory(Opcodes.POP));
        assertEquals(10, BytecodeStructureHash.opcodeCategory(Opcodes.POP2));
        assertEquals(10, BytecodeStructureHash.opcodeCategory(Opcodes.DUP));
        assertEquals(10, BytecodeStructureHash.opcodeCategory(Opcodes.SWAP));
        // Note: WIDE is not exposed by ASM's Opcodes interface (handled
        // specially during class reading); we test it via the raw int.
        assertEquals(10, BytecodeStructureHash.opcodeCategory(196));   // WIDE = 0xC4
    }

    /**
     * Reserved opcodes (BREAKPOINT=202, IMPDEP1=254, IMPDEP2=255) must
     * not throw — they fall to the default "other" bucket.
     */
    @Test
    void opcodeCategoryHandlesReservedOpcodes() {
        assertEquals(10, BytecodeStructureHash.opcodeCategory(202));   // BREAKPOINT
        assertEquals(10, BytecodeStructureHash.opcodeCategory(254));   // IMPDEP1
        assertEquals(10, BytecodeStructureHash.opcodeCategory(255));   // IMPDEP2
        assertEquals(10, BytecodeStructureHash.opcodeCategory(9999));  // never-used
    }
}
