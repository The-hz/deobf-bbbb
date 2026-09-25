package io.github.jarremapper.decompiler;

import java.nio.file.Path;
import java.util.Map;

/**
 * Adapter for <a href="https://github.com/Vineflower/vineflower">Vineflower</a>
 * ({@code org.vineflower:vineflower}).
 *
 * <p>CLI shape: same as Fernflower (it's a fork of Fernflower).
 * {@code java -cp vineflower.jar org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler <input> <outputDir> [opts]}</p>
 *
 * <p>Vineflower accepts the standard Fernflower options plus a few extras.
 * The default option set here leans toward Vineflower's strengths (lambda
 * decompilation, J17+ pattern matching).</p>
 */
public class VineflowerAdapter extends ProcessDecompiler {

    private final Path jar;

    public VineflowerAdapter(Path jar) {
        super();
        this.jar = jar;
    }

    public VineflowerAdapter(Path jar, long timeoutMinutes) {
        super(timeoutMinutes);
        this.jar = jar;
    }

    @Override public String name() { return "Vineflower"; }
    @Override public Path jarPath() { return jar; }
    @Override public String mainClassName() {
        return "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler";
    }

    @Override
    protected String[] buildArgs(Path input, Path outputDir, Map<String, String> options) {
        java.util.List<String> args = new java.util.ArrayList<>();
        // Vineflower CLI syntax: [--opt=val ...] <source> <destination>.
        // Options MUST come before source and destination.
        args.add("-hdc=0");
        args.add("-dgs=1");
        args.add("-rsy=1");
        args.add("-rns=1");
        args.add("-ibt=1");    // inline J17+ patterns
        args.add("-log=ERROR");
        options.forEach((k, v) -> {
            String val = (v == null || v.isEmpty()) ? "" : v;
            String opt = k.startsWith("-") ? k : "-" + k;
            args.add(val.isEmpty() ? opt : opt + "=" + val);
        });
        args.add(input.toAbsolutePath().toString());
        args.add(outputDir.toAbsolutePath().toString());
        return args.toArray(new String[0]);
    }
}
