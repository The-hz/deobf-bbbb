package io.github.jarremapper.pipeline;

import io.github.jarremapper.config.AppConfig;
import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.pipeline.stages.ApplyMappingStage;
import io.github.jarremapper.pipeline.stages.DecompileStage;
import io.github.jarremapper.pipeline.stages.MatchStage;
import io.github.jarremapper.pipeline.stages.ParseTargetStage;
import io.github.jarremapper.pipeline.stages.ParseUnmappedStage;
import io.github.jarremapper.pipeline.stages.ReviewStage;
import io.github.jarremapper.pipeline.stages.WriteTinyStage;

import java.util.List;

/**
 * Orchestrates the full remap-and-decompile workflow as a sequence of
 * {@link PipelineStage}s:
 *
 * <ol>
 *   <li>{@link ParseTargetStage} — parse target JAR + existing mapping</li>
 *   <li>{@link ParseUnmappedStage} — parse unmapped JAR</li>
 *   <li>{@link MatchStage} — LSH + parallel pairwise matching</li>
 *   <li>{@link ReviewStage} — decompile + user-review unmatched classes</li>
 *   <li>{@link ApplyMappingStage} — apply mapping → {@code output/remapped.jar}</li>
 *   <li>{@link DecompileStage} — decompile the remapped JAR → {@code output/sources/}</li>
 *   <li>{@link WriteTinyStage} — write Tiny v2 → {@code output/mappings.tiny}</li>
 * </ol>
 *
 * <p>Heavy work runs on the calling thread; the GUI is responsible for
 * running the pipeline on a background executor and forwarding callback
 * events back onto the FX thread via {@code Platform.runLater}.</p>
 *
 * <p>The previous monolithic implementation has been split into Stage
 * classes to make each phase independently testable and to allow
 * {@code MatchStage} to use {@code parallelStream()} without leaking
 * parallelism into the unrelated sequential phases.</p>
 */
public class RemapPipeline {

    private static final List<PipelineStage> STAGES = List.of(
            new ParseTargetStage(),
            new ParseUnmappedStage(),
            new MatchStage(),
            new ReviewStage(),
            new ApplyMappingStage(),
            new DecompileStage(),
            new WriteTinyStage()
    );

    private final AppConfig config;
    private final PipelineCallback callback;

    public RemapPipeline(AppConfig config, PipelineCallback callback) {
        this.config = config;
        this.callback = callback;
    }

    /**
     * Run all stages sequentially. Each stage reads its inputs from the
     * shared {@link PipelineContext} and writes its outputs back.
     *
     * @return the assembled {@link MappingModel} that was applied to the
     *         unmapped JAR (also written to {@code output/mappings.tiny}).
     * @throws Exception if any stage failed — the error propagates to the
     *         caller (the GUI's {@code Task.failed()} handler).
     */
    public MappingModel execute() throws Exception {
        validateInputs();
        PipelineContext ctx = new PipelineContext(config, callback);
        for (PipelineStage stage : STAGES) {
            if (callback.isCancelled()) {
                throw new InterruptedException("User cancelled before " + stage.name());
            }
            callback.onLog("→ Stage: " + stage.name());
            stage.run(ctx);
        }
        return ctx.outMapping;
    }

    private void validateInputs() throws Exception {
        if (config.targetJar() == null)
            throw new IllegalArgumentException("Target JAR is not set.");
        if (config.mappingFile() == null)
            throw new IllegalArgumentException("Mapping file is not set.");
        if (config.unmappedJar() == null)
            throw new IllegalArgumentException("Unmapped JAR is not set.");
        if (!java.nio.file.Files.isRegularFile(config.targetJar()))
            throw new IllegalArgumentException("Target JAR not a regular file: " + config.targetJar());
        if (!java.nio.file.Files.isRegularFile(config.mappingFile()))
            throw new IllegalArgumentException("Mapping file not a regular file: " + config.mappingFile());
        if (!java.nio.file.Files.isRegularFile(config.unmappedJar()))
            throw new IllegalArgumentException("Unmapped JAR not a regular file: " + config.unmappedJar());
    }
}
