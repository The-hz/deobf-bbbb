package io.github.jarremapper.pipeline.stages;

import io.github.jarremapper.parser.JarParser;
import io.github.jarremapper.pipeline.PipelineCallback;
import io.github.jarremapper.pipeline.PipelineContext;
import io.github.jarremapper.pipeline.PipelineStage;

import java.util.jar.JarFile;

/**
 * Stage 2: parse the unmapped JAR (obfuscated, no mapping) into
 * {@link ClassInfo} trees.
 */
public final class ParseUnmappedStage implements PipelineStage {

    @Override public String name() { return "Parse unmapped JAR"; }

    @Override
    public void run(PipelineContext ctx) throws Exception {
        PipelineCallback cb = ctx.callback;
        cb.onProgress(0.15, name());
        cb.onLog("Parsing unmapped JAR: " + ctx.config.unmappedJar());

        try (JarFile jar = new JarFile(ctx.config.unmappedJar().toFile())) {
            ctx.unmappedParsed = new JarParser().parseAll(jar);
        }
        cb.onLog("  parsed " + ctx.unmappedParsed.size() + " classes from unmapped JAR.");
    }
}
