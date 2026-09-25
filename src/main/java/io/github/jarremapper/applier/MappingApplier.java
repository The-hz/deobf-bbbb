package io.github.jarremapper.applier;

import io.github.jarremapper.model.MappingModel;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Applies a {@link MappingModel} to an obfuscated JAR, producing a
 * <em>remapped</em> JAR whose class files, method names, and field names
 * have been rewritten according to the mapping.
 *
 * <p>Implementation: ASM {@link ClassRemapper} wraps a {@link ClassWriter}
 * for each {@code .class} entry. The {@link Remapper} implementation maps
 * class internal names, method names (per owner+desc), and field names
 * (per owner+desc) by consulting the {@link MappingModel}.</p>
 *
 * <p>Non-class entries (resources, manifests, signatures) are copied
 * verbatim except for cryptographic signature files
 * ({@code META-INF/*.SF}, {@code *.RSA}, {@code *.DSA}) which are skipped
 * because remapping invalidates them.</p>
 */
public class MappingApplier {

    private static final Logger LOG = LoggerFactory.getLogger(MappingApplier.class);

    private final MappingModel model;
    private final Remapper remapper;

    public MappingApplier(MappingModel model) {
        this.model = model;
        this.remapper = new ModelRemapper(model);
    }

    public void apply(Path sourceJar, Path outputJar) throws IOException {
        if (!Files.isRegularFile(sourceJar)) {
            throw new IOException("Source JAR not a regular file: " + sourceJar);
        }
        Files.createDirectories(outputJar.getParent() == null ? Path.of(".") : outputJar.getParent());

        try (JarFile in = new JarFile(sourceJar.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(outputJar))) {

            Enumeration<JarEntry> entries = in.entries();
            int totalClasses = 0, totalResources = 0, skippedSignatures = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    remapClassEntry(in, entry, out);
                    totalClasses++;
                } else if (isSignatureFile(entry.getName())) {
                    skippedSignatures++;
                } else {
                    copyEntry(in, entry, out);
                    totalResources++;
                }
            }
            LOG.info("MappingApplier: remapped {} class(es), copied {} resource(s), skipped {} signature file(s).",
                    totalClasses, totalResources, skippedSignatures);
        }
    }

    private void remapClassEntry(JarFile in, JarEntry entry, JarOutputStream out) throws IOException {
        byte[] bytes;
        try (InputStream is = in.getInputStream(entry)) {
            bytes = is.readAllBytes();
        }
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(0);
        ClassRemapper visitor = new ClassRemapper(cw, remapper);
        cr.accept(visitor, 0);
        byte[] outBytes = cw.toByteArray();

        String mappedInternal = remapper.map(cr.getClassName());
        String newEntryName = mappedInternal + ".class";
        JarEntry newEntry = new JarEntry(newEntryName);
        // Preserve a manifest-stripped timestamp & compression setting.
        newEntry.setMethod(ZipEntry.DEFLATED);
        out.putNextEntry(newEntry);
        out.write(outBytes);
        out.closeEntry();
    }

    private void copyEntry(JarFile in, JarEntry entry, JarOutputStream out) throws IOException {
        JarEntry newEntry = new JarEntry(entry.getName());
        newEntry.setMethod(ZipEntry.DEFLATED);
        out.putNextEntry(newEntry);
        try (InputStream is = in.getInputStream(entry)) {
            is.transferTo(out);
        }
        out.closeEntry();
    }

    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String upper = name.toUpperCase();
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
               upper.endsWith(".EC");
    }

    /**
     * The actual remapper implementation backed by a {@link MappingModel}.
     *
     * <p>Methods that are not present in the mapping are returned unchanged
     * (so e.g. JDK methods like {@code Object.equals(Object)} are not
     * accidentally renamed).</p>
     */
    private static final class ModelRemapper extends Remapper {
        private final MappingModel model;

        ModelRemapper(MappingModel model) {
            this.model = model;
        }

        @Override
        public String map(String internalName) {
            return model.mapClass(internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return model.mapMethod(owner, name, descriptor);
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            // invokedynamic names are typically stable (lambdas, string concat,
            // records) — leave them alone.
            return name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return model.mapField(owner, name, descriptor);
        }

        @Override
        public String mapRecordComponentName(String name, String descriptor, String signature) {
            return model.mapField(null, name, descriptor);
        }
    }
}
