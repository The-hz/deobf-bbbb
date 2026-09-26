package io.github.jarremapper.pipeline.stages;

import io.github.jarremapper.pipeline.PipelineCallback;
import io.github.jarremapper.pipeline.PipelineContext;
import io.github.jarremapper.pipeline.PipelineStage;
import io.github.jarremapper.writer.TinyWriter;

/**
 * Stage 7: serialize the assembled mapping to Tiny v2 in
 * {@code output/mappings.tiny}.
 */
public final class WriteTinyStage implements PipelineStage {

    @Override public String name() { return "Write Tiny v2 mapping"; }

    @Override
    public void run(PipelineContext ctx) throws Exception {
        PipelineCallback cb = ctx.callback;
        cb.onProgress(0.95, name());

        ctx.tinyFile = ctx.outputDir().resolve("mappings.tiny");
        new TinyWriter(ctx.outMapping).write(ctx.tinyFile);
        cb.onLog("Wrote Tiny v2 mapping: " + ctx.tinyFile);

        cb.onProgress(1.0, "Done");
        cb.onLog("Pipeline finished. Output: " + ctx.outputDir());
    }
}
