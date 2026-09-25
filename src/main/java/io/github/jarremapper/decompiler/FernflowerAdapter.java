package io.github.jarremapper.decompiler;

import java.nio.file.Path;
import java.util.Map;

/**
 * Adapter for the JetBrains-published Fernflower
 * ({@code org.jetbrains.intellij.deps:fernflower}).
 *
 * <p>CLI shape:
 * {@code java -cp fernflower.jar org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler <input> <outputDir> [opts]}</p>
 *
 * <p>Options are passed as {@code -key=value}.</p>
 */
public class FernflowerAdapter extends ProcessDecompiler {

    private final Path jar;

    public FernflowerAdapter(Path jar) {
        this.jar = jar;
    }

    @Override public String name() { return "Fernflower"; }
    @Override public Path jarPath() { return jar; }
    @Override public String mainClassName() {
        return "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler";
    }

    @Override
    protected String[] buildArgs(Path input, Path outputDir, Map<String, String> options) {
        java.util.List<String> args = new java.util.ArrayList<>();
        // Vineflower / Quiltflower / Fernflower CLI ordering is:
        //   [--opt=val ...] <source> <destination>
        // i.e. options MUST come before the source and destination.
        args.add("-hdc=0");     // hide default constructor
        args.add("-dgs=1");     // decompile generic signatures
        args.add("-rsy=1");    // remove synthetic methods
        args.add("-rns=1");    // remove synthetic class names
        args.add("-log=ERROR");
        // User-supplied overrides — already in -key=value form.
        options.forEach((k, v) -> {
            String val = (v == null || v.isEmpty()) ? "" : v;
            String opt = k.startsWith("-") ? k : "-" + k;
            args.add(val.isEmpty() ? opt : opt + "=" + val);
        });
        // Then source + destination.
        args.add(input.toAbsolutePath().toString());
        args.add(outputDir.toAbsolutePath().toString());
        return args.toArray(new String[0]);
    }
}
