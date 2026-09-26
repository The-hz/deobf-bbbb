package io.github.jarremapper.pipeline.stages;

import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.model.TargetClassInfo;
import io.github.jarremapper.parser.JarParser;
import io.github.jarremapper.parser.MappingParsers;
import io.github.jarremapper.pipeline.PipelineCallback;
import io.github.jarremapper.pipeline.PipelineContext;
import io.github.jarremapper.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Stage 1: parse the target JAR (the one that already has a mapping file)
 * into {@link ClassInfo} trees, and parse the user-supplied mapping file
 * into a {@link MappingModel}. Cross-join to produce a list of
 * {@link TargetClassInfo}.
 */
public final class ParseTargetStage implements PipelineStage {

    @Override public String name() { return "Parse target JAR + existing mapping"; }

    @Override
    public void run(PipelineContext ctx) throws Exception {
        PipelineCallback cb = ctx.callback;
        cb.onProgress(0.05, name());
        cb.onLog("Parsing target JAR: " + ctx.config.targetJar());

        try (JarFile jar = new JarFile(ctx.config.targetJar().toFile())) {
            ctx.targetParsed = new JarParser().parseAll(jar);
        }
        cb.onLog("  parsed " + ctx.targetParsed.size() + " classes from target JAR.");

        ctx.targetMapping = MappingParsers.parse(ctx.config.mappingFile());
        cb.onLog("  loaded mapping with " + ctx.targetMapping.size() + " class entries.");

        // Build the cross-join: only target classes that actually have a
        // mapping entry contribute to the matcher's search space.
        ctx.targets = new ArrayList<>();
        for (MappingModel.ClassEntry ce : ctx.targetMapping.entries()) {
            ClassInfo info = ctx.targetParsed.get(ce.obfName());
            if (info == null) continue;
            ctx.targets.add(new TargetClassInfo(info, ce.origName()));
        }
        cb.onLog("  cross-joined to " + ctx.targets.size() + " mappable target classes.");

        // The out-mapping inherits the namespace pair from the target mapping
        // so downstream tools see consistent column headers in the tiny file.
        ctx.outMapping = new MappingModel();
        ctx.outMapping.setNamespaces(ctx.targetMapping.obfNamespace(),
                                      ctx.targetMapping.namedNamespace());
    }
}
