package io.github.jarremapper.pipeline;

import io.github.jarremapper.config.AppConfig;
import io.github.jarremapper.matcher.SimilarityMatcher;
import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.model.TargetClassInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mutable shared state passed stage-to-stage by {@link RemapPipeline}.
 *
 * <p>Each {@link PipelineStage} reads its inputs from this context and writes
 * its outputs back. The fields are intentionally mutable and unsynchronized —
 * a stage is responsible for any thread-safety it needs (e.g.
 * {@code MatchStage} uses {@code parallelStream()} and feeds results into a
 * {@link java.util.concurrent.ConcurrentLinkedQueue}).</p>
 *
 * <p>The context is created once per pipeline run; stages do not survive
 * beyond a single run.</p>
 */
public final class PipelineContext {

    // ----- inputs (set once at construction) -----
    public final AppConfig config;
    public final PipelineCallback callback;

    // ----- shared services -----
    public SimilarityMatcher matcher = new SimilarityMatcher();

    // ----- Stage 1 outputs: parse target JAR + existing mapping -----
    /** obf-class-name → {@link ClassInfo} parsed from the target JAR. */
    public Map<String, ClassInfo> targetParsed;
    /** Mapping loaded from the user-supplied mapping file. */
    public MappingModel targetMapping;
    /** {@link TargetClassInfo} list = (ClassInfo, origName) for each class
     * in {@code targetMapping} that was also found in {@code targetParsed}. */
    public List<TargetClassInfo> targets;

    // ----- Stage 2 outputs: parse unmapped JAR -----
    /** obf-class-name → {@link ClassInfo} parsed from the unmapped JAR. */
    public Map<String, ClassInfo> unmappedParsed;

    // ----- Stage 3 outputs: similarity matching -----
    /** The "out" mapping being assembled — populated by Stages 3 and 4. */
    public MappingModel outMapping;
    /** Unmatched classes queued for Stage 4 (decompile + user review). */
    public List<ClassInfo> unmatched = new ArrayList<>();

    // ----- Stage 5 outputs: apply mapping → remapped.jar -----
    public Path remappedJar;

    // ----- Stage 6 outputs: decompile remapped.jar → sources/ -----
    public Path sourcesDir;

    // ----- Stage 7 outputs: write tiny v2 mapping -----
    public Path tinyFile;

    public PipelineContext(AppConfig config, PipelineCallback callback) {
        this.config = config;
        this.callback = callback;
    }

    /** Convenience accessor for the similarity threshold. */
    public double threshold() { return config.similarityThreshold(); }

    /** Convenience accessor for the decompiler name. */
    public String decompilerName() { return config.decompilerName(); }

    /** Convenience accessor for the output directory. */
    public Path outputDir() { return config.outputDir(); }
}
