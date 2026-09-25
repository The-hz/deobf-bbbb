package io.github.jarremapper.decompiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight registry that builds {@link Decompiler} instances on demand
 * from the jars located on the class path.
 *
 * <p>Lookup is by the {@link Decompiler#name()} string ("CFR",
 * "Fernflower", "Vineflower"). If a jar is missing, {@link #get(String)}
 * throws a clear {@link IllegalStateException} pointing at the missing
 * Maven coordinate.</p>
 */
public final class DecompilerRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DecompilerRegistry.class);

    private DecompilerRegistry() {}

    /** Build a fresh adapter for the named decompiler, locating its jar lazily. */
    public static Decompiler get(String name) {
        try {
            return switch (name) {
                case "CFR"         -> new CfrAdapter(DecompilerLocator.locate("cfr"));
                // Fernflower is published as 'org.quiltmc:quiltflower' on Maven
                // Central (an older fork of Fernflower, still maintained). Users
                // can also drop a JetBrains 'fernflower.jar' into ./lib/ — both
                // share the main class name we invoke. Try fernflower first,
                // fall back to quiltflower.
                case "Fernflower" -> new FernflowerAdapter(locateFernflowerLike());
                case "Vineflower" -> new VineflowerAdapter(DecompilerLocator.locate("vineflower"));
                default           -> throw new IllegalArgumentException(
                        "Unknown decompiler: " + name);
            };
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Decompiler '" + name + "' jar not found on the runtime class path.\n" +
                            "Make sure the corresponding implementation dependency is present in build.gradle.\n" +
                            "Original error: " + e.getMessage(), e);
        }
    }

    /**
     * Locate a Fernflower-compatible jar: try "fernflower" first (for users
     * who drop the JetBrains Fernflower jar into {@code lib/}), then fall
     * back to "quiltflower" (QuiltMC's Maven Central artifact).
     */
    private static java.nio.file.Path locateFernflowerLike() throws IOException {
        try {
            return DecompilerLocator.locate("fernflower");
        } catch (IOException ex) {
            return DecompilerLocator.locate("quiltflower");
        }
    }

    /** Map of {@code name → jarPath} for the GUI to display availability. */
    public static Map<String, Path> available() {
        Map<String, Path> out = new LinkedHashMap<>();
        for (String n : new String[]{"CFR", "Fernflower", "Vineflower"}) {
            try {
                if (n.equals("Fernflower")) {
                    out.put(n, locateFernflowerLike());
                } else {
                    out.put(n, DecompilerLocator.locate(n.toLowerCase()));
                }
            } catch (IOException e) {
                LOG.debug("Unavailable: {} ({})", n, e.getMessage());
            }
        }
        return out;
    }

    /** Default decompiler name — used by the GUI when the user hasn't picked one. */
    public static final String DEFAULT = "CFR";
}
