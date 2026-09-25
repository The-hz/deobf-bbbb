package io.github.jarremapper.decompiler;

import java.nio.file.Path;
import java.util.Map;

/**
 * Adapter for <a href="https://github.com/leibnitz27/cfr">CFR</a>
 * ({@code org.benf:cfr:0.152}). Default decompiler in this app.
 *
 * <p>CLI shape: {@code java -cp cfr.jar org.benf.cfr.reader.Main <input> --outputdir <dir> [opts]}</p>
 */
public class CfrAdapter extends ProcessDecompiler {

    private final Path jar;

    public CfrAdapter(Path jar) {
        this.jar = jar;
    }

    @Override public String name() { return "CFR"; }
    @Override public Path jarPath() { return jar; }
    @Override public String mainClassName() { return "org.benf.cfr.reader.Main"; }

    @Override
    protected String[] buildArgs(Path input, Path outputDir, Map<String, String> options) {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(input.toAbsolutePath().toString());
        args.add("--outputdir");
        args.add(outputDir.toAbsolutePath().toString());
        args.add("--silent");
        args.add("true");
        // Sensible defaults — verified against `java -jar cfr.jar --help`
        // for CFR 0.152. Booleans accept "true"/"false" as values.
        args.add("--comments");
        args.add("false");
        args.add("--decodeenumswitch");
        args.add("true");
        args.add("--decodelambdas");
        args.add("true");
        args.add("--decodefinally");
        args.add("true");
        // User-supplied overrides — caller passes them as `key -> value`,
        // where key is the CFR option name without the `--` prefix.
        options.forEach((k, v) -> {
            args.add("--" + k);
            if (v != null && !v.isEmpty() && !"true".equalsIgnoreCase(v)) {
                args.add(v);
            }
        });
        return args.toArray(new String[0]);
    }
}
