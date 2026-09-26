package io.github.jarremapper.pipeline.stages;

import io.github.jarremapper.decompiler.Decompiler;
import io.github.jarremapper.decompiler.DecompilerRegistry;
import io.github.jarremapper.pipeline.PipelineCallback;
import io.github.jarremapper.pipeline.PipelineContext;
import io.github.jarremapper.pipeline.PipelineStage;

import java.nio.file.Files;
import java.util.HashMap;

/**
 * Stage 6: decompile the remapped JAR into {@code output/sources/} using
 * the user-selected decompiler.
 */
public final class DecompileStage implements PipelineStage {

    @Override public String name() { return "Decompile remapped JAR → sources/"; }

    @Override
    public void run(PipelineContext ctx) throws Exception {
        PipelineCallback cb = ctx.callback;
        cb.onProgress(0.85, name());

        ctx.sourcesDir = ctx.outputDir().resolve("sources");
        Files.createDirectories(ctx.sourcesDir);

        Decompiler dec = DecompilerRegistry.get(ctx.decompilerName(),
                ctx.config.decompilerTimeoutMinutes());
        dec.decompile(ctx.remappedJar, ctx.sourcesDir, new HashMap<>());
        cb.onLog("Decompiled remapped JAR → " + ctx.sourcesDir);
    }
}
