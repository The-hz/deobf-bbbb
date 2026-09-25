package io.github.jarremapper.config;

import java.nio.file.Path;

/**
 * Runtime configuration container. All fields are immutable; the GUI uses
 * a fresh {@link Builder} to mutate settings before launching the pipeline.
 *
 * <p>Default values:
 * <ul>
 *   <li>similarity threshold: {@code 0.70} (70%)</li>
 *   <li>decompiler: {@code "CFR"} (per {@link io.github.jarremapper.decompiler.DecompilerRegistry#DEFAULT})</li>
 *   <li>output directory: {@code ./output}</li>
 * </ul>
 */
public record AppConfig(
        Path targetJar,
        Path mappingFile,
        Path unmappedJar,
        Path outputDir,
        double similarityThreshold,
        String decompilerName,
        int decompilerTimeoutMinutes
) {

    public static final double DEFAULT_THRESHOLD = 0.70;
    public static final String DEFAULT_OUTPUT_DIR = "./output";
    public static final int DEFAULT_DECOMPILER_TIMEOUT_MIN = 15;

    public static Builder builder() {
        return new Builder();
    }

    public static AppConfig defaults() {
        return builder().build();
    }

    public static final class Builder {
        private Path targetJar;
        private Path mappingFile;
        private Path unmappedJar;
        private Path outputDir = Path.of(DEFAULT_OUTPUT_DIR).toAbsolutePath();
        private double similarityThreshold = DEFAULT_THRESHOLD;
        private String decompilerName = "CFR";
        private int decompilerTimeoutMinutes = DEFAULT_DECOMPILER_TIMEOUT_MIN;

        public Builder targetJar(Path v)             { this.targetJar = v; return this; }
        public Builder mappingFile(Path v)            { this.mappingFile = v; return this; }
        public Builder unmappedJar(Path v)            { this.unmappedJar = v; return this; }
        public Builder outputDir(Path v)             { this.outputDir = v; return this; }
        public Builder similarityThreshold(double v) { this.similarityThreshold = v; return this; }
        public Builder decompilerName(String v)        { this.decompilerName = v; return this; }
        public Builder decompilerTimeoutMinutes(int v){ this.decompilerTimeoutMinutes = v; return this; }

        public AppConfig build() {
            return new AppConfig(
                    targetJar, mappingFile, unmappedJar, outputDir,
                    similarityThreshold, decompilerName, decompilerTimeoutMinutes);
        }
    }
}
