package io.github.jarremapper.pipeline.stages;

import io.github.jarremapper.decompiler.Decompiler;
import io.github.jarremapper.decompiler.DecompilerRegistry;
import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.pipeline.PipelineCallback;
import io.github.jarremapper.pipeline.PipelineContext;
import io.github.jarremapper.pipeline.PipelineStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
/**
 * Stage 4: for every class that Stage 3 couldn't match above threshold,
 * decompile it (single-class mini-JAR → decompiler → Java source), show
 * the user the source via {@link PipelineCallback#askForName}, and either
 * apply the user-chosen name or fall back to an identity mapping.
 */
public final class ReviewStage implements PipelineStage {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewStage.class);

    @Override public String name() { return "Decompile & review unmatched classes"; }

    @Override
    public void run(PipelineContext ctx) throws Exception {
        PipelineCallback cb = ctx.callback;
        int total = ctx.unmatched.size();
        if (total == 0) {
            cb.onLog("No unmatched classes — skipping review stage.");
            return;
        }
        cb.onLog("Reviewing " + total + " unmatched class(es).");

        int idx = 0;
        for (ClassInfo u : ctx.unmatched) {
            if (cb.isCancelled()) throw new InterruptedException("User cancelled.");
            idx++;
            cb.onProgress(0.45 + 0.20 * (idx / (double) total),
                    "Reviewing " + u.internalName() + " (" + idx + "/" + total + ")");

            String source = decompileSingleClass(ctx.config.unmappedJar(), u,
                    ctx.decompilerName(), ctx.config.decompilerTimeoutMinutes());
            String chosen = cb.askForName(u, source);
            if (chosen == null || chosen.isBlank()) {
                ctx.outMapping.markIdentity(u.internalName());
                cb.onLog("  skipped " + u.internalName() + " → identity mapping.");
            } else {
                String internal = chosen.replace('.', '/');
                ctx.outMapping.put(u.internalName(), internal);
                cb.onLog("  renamed " + u.internalName() + " → " + internal);
            }
        }
    }

    /**
     * Extract the single {@code .class} entry from the unmapped JAR into
     * a temp mini-JAR, then run the decompiler on it so the user can read
     * the source while reviewing.
     */
    private String decompileSingleClass(Path unmappedJar, ClassInfo info,
                                         String decompilerName,
                                         int timeoutMinutes) throws Exception {
        String obfName = info.internalName();
        String entryName = obfName + ".class";
        Path tempJar = Files.createTempFile("jr-single-", ".jar");
        try {
            try (JarFile src = new JarFile(unmappedJar.toFile());
                 JarOutputStream out = new JarOutputStream(Files.newOutputStream(tempJar))) {
                Enumeration<JarEntry> entries = src.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (!entryName.equals(e.getName())) continue;
                    JarEntry ne = new JarEntry(entryName);
                    ne.setMethod(ZipEntry.DEFLATED);
                    out.putNextEntry(ne);
                    try (var is = src.getInputStream(e)) {
                        is.transferTo(out);
                    }
                    out.closeEntry();
                }
            }

            Path decompDir = Files.createTempDirectory("jr-decomp-");
            Decompiler dec = DecompilerRegistry.get(decompilerName, timeoutMinutes);
            try {
                dec.decompile(tempJar, decompDir, new HashMap<>());
            } catch (Exception ex) {
                LOG.warn("Single-class decompile failed for {}: {}", obfName, ex.toString());
                return "";
            }
            Path javaFile = findJavaFile(decompDir, obfName);
            if (javaFile == null || !Files.exists(javaFile)) return "";
            return Files.readString(javaFile);
        } finally {
            try { Files.deleteIfExists(tempJar); } catch (IOException ignored) {}
        }
    }

    private Path findJavaFile(Path root, String obfName) throws IOException {
        Path expected = root.resolve(obfName.replace('/', java.io.File.separatorChar) + ".java");
        if (Files.exists(expected)) return expected;
        try (var stream = Files.walk(root)) {
            var match = stream.filter(p -> p.getFileName() != null &&
                    p.getFileName().toString().endsWith(".java")).findFirst();
            return match.orElse(null);
        }
    }
}
