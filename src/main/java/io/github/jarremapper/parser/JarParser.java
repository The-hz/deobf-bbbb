package io.github.jarremapper.parser;

import io.github.jarremapper.matcher.BytecodeStructureHash;
import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.model.FieldInfo;
import io.github.jarremapper.model.MemberKey;
import io.github.jarremapper.model.MethodInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Walks a JAR file and produces a {@link ClassInfo} record for every
 * {@code .class} entry it contains.
 *
 * <p>Uses {@link ClassReader#SKIP_DEBUG} but NOT {@code SKIP_CODE} — we need
 * the method bodies so we can hash instruction sequences. Method bodies are
 * collected via {@link MethodNode} (i.e. the ASM tree API on top of the
 * visitor API), since that lets us iterate the instruction list once and
 * compute the structural fingerprint without re-reading.</p>
 *
 * <p>Memory footprint: one {@link ClassInfo} + one {@link MethodNode} per
 * method per class. For typical obfuscated JARs (a few thousand classes) this
 * is fine on a 2 GiB heap; for very large JARs, consider switching to a
 * streaming fingerprint computation in a single pass.</p>
 */
public class JarParser {

    private static final Logger LOG = LoggerFactory.getLogger(JarParser.class);

    /**
     * Parse all {@code .class} entries in {@code jarFile} into a
     * name → {@link ClassInfo} map (insertion order preserved).
     */
    public Map<String, ClassInfo> parseAll(JarFile jarFile) throws IOException {
        Map<String, ClassInfo> out = new LinkedHashMap<>();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry e = entries.nextElement();
            if (!e.getName().endsWith(".class")) continue;
            try (InputStream in = jarFile.getInputStream(e)) {
                ClassReader cr = new ClassReader(in);
                ClassInfo info = parseOne(cr);
                if (info != null) {
                    out.put(info.internalName(), info);
                }
            } catch (IOException | RuntimeException ex) {
                LOG.warn("Skipping {}: {}", e.getName(), ex.toString());
            }
        }
        return out;
    }

    /**
     * Parse a single {@link ClassReader} into a {@link ClassInfo}.
     */
    public ClassInfo parseOne(ClassReader cr) {
        ClassInfo info = new ClassInfo(cr.getClassName());
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name,
                              String signature, String superName,
                              String[] interfaces) {
                info.setAccess(access);
                info.setSuperName(superName);
                info.setInterfaces(interfaces == null ? new String[0] : interfaces);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                MemberKey key = new MemberKey(name, descriptor);
                // Use a stable fingerprint of the constant initializer, if any.
                int initFp = value == null ? 0 : System.identityHashCode(value.getClass());
                info.addField(new FieldInfo(key, access, initFp));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                // Use MethodNode to capture the entire body, then hash it at visitEnd.
                return new MethodNode(Opcodes.ASM9, access, name, descriptor,
                                      signature, exceptions) {
                    private boolean hashed = false;

                    @Override
                    public void visitEnd() {
                        super.visitEnd();
                        if (hashed) return;
                        hashed = true;
                        BytecodeStructureHash.Fingerprint fp =
                                BytecodeStructureHash.analyze(this);
                        MemberKey key = new MemberKey(name, descriptor);
                        info.addMethod(new MethodInfo(key, access, fp.hash(),
                                fp.coarseHash(),
                                fp.maxStack(), fp.maxLocals(), fp.insnCount()));
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return info;
    }

    /**
     * Convenience: parse a {@link JarFile} and return a list of
     * {@link ClassInfo} ordered as encountered (parallel to the underlying
     * {@code ZipFile}'s natural entry order, which is usually alpha-sorted
     * by entry name).
     */
    public List<ClassInfo> parseList(JarFile jarFile) throws IOException {
        return new ArrayList<>(parseAll(jarFile).values());
    }
}
