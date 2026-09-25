package io.github.jarremapper.model;

/**
 * Lightweight carrier for a class on the target side (the JAR that already has
 * a mapping). It bundles the ASM-parsed structure {@link ClassInfo} together
 * with the original (deobfuscated) name taken from the mapping file.
 *
 * <p>This is what the matcher compares the unmapped-JAR classes against.</p>
 */
public record TargetClassInfo(ClassInfo info, String origName) {

    /** Convenience: obfuscated internal name from {@link ClassInfo}. */
    public String obfName() {
        return info.internalName();
    }

    @Override
    public String toString() {
        return "Target[" + obfName() + " => " + origName + "]";
    }
}
