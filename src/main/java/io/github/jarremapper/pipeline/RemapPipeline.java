package io.github.jarremapper.pipeline;

import io.github.jarremapper.config.AppConfig;
import io.github.jarremapper.decompiler.Decompiler;
import io.github.jarremapper.decompiler.DecompilerRegistry;
import io.github.jarremapper.matcher.SimilarityMatcher;
import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.model.MatchResult;
import io.github.jarremapper.model.MemberKey;
import io.github.jarremapper.model.MethodInfo;
import io.github.jarremapper.model.TargetClassInfo;
import io.github.jarremapper.parser.JarParser;
import io.github.jarremapper.parser.MappingParsers;
import io.github.jarremapper.applier.MappingApplier;
import io.github.jarremapper.writer.TinyWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Orchestrates the full remap-and-decompile workflow:
 *
 * <ol>
 *   <li>parse target JAR + existing mapping file</li>
 *   <li>parse unmapped JAR</li>
 *   <li>compute pairwise similarity; create mappings for matches ≥ threshold</li>
 *   <li>for unmatched classes — decompile (single-class), ask user for a name
 *       via the callback; "Skip" → identity mapping</li>
 *   <li>apply the assembled mapping to the unmapped JAR → {@code output/remapped.jar}</li>
 *   <li>decompile the remapped JAR → {@code output/sources/}</li>
 *   <li>write the Tiny v2 mapping file → {@code output/mappings.tiny}</li>
 * </ol>
 *
 * <p>All heavy work happens on the calling thread; the GUI is responsible for
 * running the pipeline on a background executor and forwarding callback events
 * back onto the FX thread via {@code Platform.runLater}.</p>
 */
public class RemapPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(RemapPipeline.class);

    private final AppConfig config;
    private final PipelineCallback callback;

    public RemapPipeline(AppConfig config, PipelineCallback callback) {
        this.config = config;
        this.callback = callback;
    }

    /**
     * Run the pipeline.
     *
     * @return the populated {@link MappingModel} that was applied to the JAR
     *         (also written to {@code output/mappings.tiny})
     */
    public MappingModel execute() throws Exception {
        Path targetJar      = config.targetJar();
        Path mappingFile     = config.mappingFile();
        Path unmappedJar     = config.unmappedJar();
        Path outputDir       = config.outputDir();
        double threshold     = config.similarityThreshold();
        String decompilerName = config.decompilerName();

        validateInputs(targetJar, mappingFile, unmappedJar);
        Files.createDirectories(outputDir);

        // ----- Stage 1: parse target JAR + mapping -----
        progress(0.05, "Parsing target JAR + existing mapping");
        Map<String, ClassInfo> targetParsed = parseJar(targetJar, "target");
        MappingModel targetMapping = MappingParsers.parse(mappingFile);
        List<TargetClassInfo> targets = buildTargetList(targetParsed, targetMapping);
        log("Parsed " + targets.size() + " classes from target JAR; mapping has " + targetMapping.size() + " entries.");

        // ----- Stage 2: parse unmapped JAR -----
        progress(0.15, "Parsing unmapped JAR");
        Map<String, ClassInfo> unmappedParsed = parseJar(unmappedJar, "unmapped");
        log("Parsed " + unmappedParsed.size() + " classes from unmapped JAR.");

        // ----- Stage 3: similarity matching -----
        progress(0.25, "Matching by similarity");
        SimilarityMatcher matcher = new SimilarityMatcher();
        MappingModel outMapping = new MappingModel();
        outMapping.setNamespaces(targetMapping.obfNamespace(), targetMapping.namedNamespace());

        List<ClassInfo> unmatched = new ArrayList<>();
        int matchedCount = 0;
        for (ClassInfo u : unmappedParsed.values()) {
            if (callback.isCancelled()) throw new InterruptedException("User cancelled.");
            var best = matcher.matchBest(u, targets, threshold);
            if (best.isPresent()) {
                MatchResult r = best.get();
                applyClassMatch(u, targetParsed, r, outMapping, targetMapping);
                matchedCount++;
                callback.onMatchResult(r);
            } else {
                // We still want a best-effort row in the table, marked as 0 score.
                MatchResult r = new MatchResult(u.internalName(), u.internalName(),
                        u.internalName(), 0.0, 0, u.methodCount(), 0, u.fieldCount());
                callback.onMatchResult(r);
                unmatched.add(u);
            }
        }
        log("Matched " + matchedCount + " classes; " + unmatched.size() + " unmatched (queued for review).");

        // ----- Stage 4: review unmatched classes -----
        progress(0.45, "Decompiling & reviewing unmatched classes");
        int decompIndex = 0;
        for (ClassInfo u : unmatched) {
            if (callback.isCancelled()) throw new InterruptedException("User cancelled.");
            decompIndex++;
            progress(0.45 + 0.20 * (decompIndex / (double) Math.max(1, unmatched.size())),
                    "Reviewing " + u.internalName() + " (" + decompIndex + "/" + unmatched.size() + ")");
            String source = decompileSingleClass(unmappedJar, u, decompilerName);
            String chosen = callback.askForName(u, source);
            if (chosen == null || chosen.isBlank()) {
                outMapping.markIdentity(u.internalName());
                log("Skipped unmatched class " + u.internalName() + " → identity mapping.");
            } else {
                String internal = chosen.replace('.', '/');
                outMapping.put(u.internalName(), internal);
                log("Renamed unmatched class " + u.internalName() + " → " + internal);
            }
        }

        // ----- Stage 5: apply mapping → remapped.jar -----
        progress(0.70, "Applying mapping to unmapped JAR");
        Path remappedJar = outputDir.resolve("remapped.jar");
        new MappingApplier(outMapping).apply(unmappedJar, remappedJar);
        log("Wrote remapped JAR: " + remappedJar);

        // ----- Stage 6: decompile remapped JAR → output/sources -----
        progress(0.85, "Decompiling remapped JAR");
        Path sourcesDir = outputDir.resolve("sources");
        Files.createDirectories(sourcesDir);
        Decompiler dec = DecompilerRegistry.get(decompilerName);
        dec.decompile(remappedJar, sourcesDir, new HashMap<>());
        log("Decompiled remapped JAR → " + sourcesDir);

        // ----- Stage 7: write tiny mapping → output/mappings.tiny -----
        progress(0.95, "Writing Tiny v2 mapping");
        Path tinyFile = outputDir.resolve("mappings.tiny");
        new TinyWriter(outMapping).write(tinyFile);
        log("Wrote Tiny v2 mapping: " + tinyFile);

        progress(1.0, "Done");
        log("Pipeline finished. Output: " + outputDir);
        return outMapping;
    }

    /* ---------- helpers ---------- */

    private void validateInputs(Path targetJar, Path mappingFile, Path unmappedJar) throws IOException {
        if (targetJar == null) throw new IOException("Target JAR is not set.");
        if (mappingFile == null) throw new IOException("Mapping file is not set.");
        if (unmappedJar == null) throw new IOException("Unmapped JAR is not set.");
        if (!Files.isRegularFile(targetJar)) throw new IOException("Target JAR not a regular file: " + targetJar);
        if (!Files.isRegularFile(mappingFile)) throw new IOException("Mapping file not a regular file: " + mappingFile);
        if (!Files.isRegularFile(unmappedJar)) throw new IOException("Unmapped JAR not a regular file: " + unmappedJar);
    }

    private Map<String, ClassInfo> parseJar(Path jarPath, String label) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return new JarParser().parseAll(jar);
        } catch (IOException ex) {
            throw new IOException("Failed to parse " + label + " JAR " + jarPath + ": " + ex.getMessage(), ex);
        }
    }

    private List<TargetClassInfo> buildTargetList(Map<String, ClassInfo> parsed, MappingModel mapping) {
        Map<String, ClassInfo> all = new LinkedHashMap<>(parsed);
        List<TargetClassInfo> out = new ArrayList<>(all.size());
        for (MappingModel.ClassEntry ce : mapping.entries()) {
            ClassInfo info = all.get(ce.obfName());
            if (info == null) continue;
            out.add(new TargetClassInfo(info, ce.origName()));
        }
        return out;
    }

    private void applyClassMatch(ClassInfo unmapped,
                                 Map<String, ClassInfo> targetParsed,
                                 MatchResult r,
                                 MappingModel outMapping,
                                 MappingModel targetMapping) {
        // Class mapping
        outMapping.put(r.unmappedObfName(), r.targetOrigName());

        // Look up the target's parsed class (the obf version) and its mapping entry
        ClassInfo tInfo = targetParsed.get(r.targetObfName());
        MappingModel.ClassEntry tEntry = targetMapping.get(r.targetObfName());
        if (tInfo == null || tEntry == null) return;

        // Method-level matching: build target method map keyed by (desc, fingerprint) → orig name
        Map<String, String> methodFpToOrig = new HashMap<>();
        for (Map.Entry<MemberKey, MethodInfo> e : tInfo.methods().entrySet()) {
            String fpKey = e.getKey().descriptor() + "#" +
                    Integer.toHexString(e.getValue().insnFingerprint());
            String orig = tEntry.methods().get(e.getKey());
            if (orig == null) orig = e.getKey().name();
            methodFpToOrig.put(fpKey, orig);
        }

        // Field-level: key by descriptor (field names unreliable after obf).
        Map<String, String> fieldDescToOrig = new HashMap<>();
        for (Map.Entry<MemberKey, String> e : tEntry.fields().entrySet()) {
            fieldDescToOrig.merge(e.getKey().descriptor(), e.getValue(),
                    (a, b) -> a);  // first wins
        }

        for (Map.Entry<MemberKey, MethodInfo> me : unmapped.methods().entrySet()) {
            MemberKey mk = me.getKey();
            String fpKey = mk.descriptor() + "#" +
                    Integer.toHexString(me.getValue().insnFingerprint());
            String orig = methodFpToOrig.get(fpKey);
            if (orig == null) {
                // Unmatched method inside a matched class → identity.
                orig = mk.name();
            }
            outMapping.putMethod(r.unmappedObfName(), r.targetOrigName(),
                    mk.name(), mk.descriptor(), orig);
        }

        for (MemberKey mk : unmapped.fields().keySet()) {
            String orig = fieldDescToOrig.get(mk.descriptor());
            if (orig == null) orig = mk.name();
            outMapping.putField(r.unmappedObfName(), r.targetOrigName(),
                    mk.name(), mk.descriptor(), orig);
        }
    }

    /**
     * Extract a single {@code .class} entry from the unmapped JAR into a
     * temporary mini-JAR, then run the decompiler on it so the user can read
     * the source while reviewing.
     */
    private String decompileSingleClass(Path unmappedJar, ClassInfo info, String decompilerName)
            throws Exception {
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
            Decompiler dec = DecompilerRegistry.get(decompilerName);
            try {
                dec.decompile(tempJar, decompDir, new HashMap<>());
            } catch (Exception ex) {
                LOG.warn("Single-class decompile failed for {}: {}", obfName, ex.toString());
                return "";
            }
            // Walk decompDir for the .java file.
            Path javaFile = findJavaFile(decompDir, obfName);
            if (javaFile == null || !Files.exists(javaFile)) return "";
            return Files.readString(javaFile);
        } finally {
            try { Files.deleteIfExists(tempJar); } catch (IOException ignored) {}
        }
    }

    private Path findJavaFile(Path root, String obfName) throws IOException {
        String name = obfName.replace('/', '.').replace('$', '.') + ".java";
        Path expected = root.resolve(obfName.replace('/', java.io.File.separatorChar) + ".java");
        if (Files.exists(expected)) return expected;
        // Fall back: walk and find by simple-name match.
        try (var stream = Files.walk(root)) {
            var match = stream.filter(p -> p.getFileName() != null &&
                    p.getFileName().toString().endsWith(".java")).findFirst();
            return match.orElse(null);
        }
    }

    private void progress(double f, String stage) {
        try {
            callback.onProgress(f, stage);
        } catch (RuntimeException ignored) {}
    }

    private void log(String msg) {
        LOG.info(msg);
        try {
            callback.onLog(msg);
        } catch (RuntimeException ignored) {}
    }
}
