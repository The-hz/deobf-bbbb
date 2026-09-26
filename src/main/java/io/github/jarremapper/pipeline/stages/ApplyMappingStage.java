package io.github.jarremapper.pipeline.stages;

import io.github.jarremapper.applier.MappingApplier;
import io.github.jarremapper.pipeline.PipelineCallback;
import io.github.jarremapper.pipeline.PipelineContext;
import io.github.jarremapper.pipeline.PipelineStage;

import java.nio.file.Files;

/**
 * Stage 5: apply the assembled {@link io.github.jarremapper.model.MappingModel}
 * to the unmapped JAR, producing {@code output/remapped.jar}.
 */
public final class ApplyMappingStage implements PipelineStage {

    @Override public String name() { return "Apply mapping → remapped.jar"; }

    @Override
    public void run(PipelineContext ctx) throws Exception {
        PipelineCallback cb = ctx.callback;
        cb.onProgress(0.70, name());

        Files.createDirectories(ctx.outputDir());
        ctx.remappedJar = ctx.outputDir().resolve("remapped.jar");

        new MappingApplier(ctx.outMapping).apply(ctx.config.unmappedJar(), ctx.remappedJar);
        cb.onLog("Wrote remapped JAR: " + ctx.remappedJar);
    }
}
