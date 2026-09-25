package io.github.jarremapper.model;

/**
 * Result of comparing one unmapped-JAR class against one target-JAR class.
 *
 * <p>Score is in {@code [0.0, 1.0]} — the weighted average of multiple signals
 * (signature similarity, method-descriptor overlap, field-type overlap,
 * instruction-fingerprint collision rate). A score ≥ user threshold means
 * the matcher will accept the mapping.</p>
 */
public record MatchResult(String unmappedObfName,
                          String targetObfName,
                          String targetOrigName,
                          double score,
                          int matchedMethods,
                          int totalMethods,
                          int matchedFields,
                          int totalFields) {

    /**
     * Convenience: is {@code score} at least {@code threshold} (a {@code [0,1]} value)?
     */
    public boolean meets(double threshold) {
        return score + 1e-9 >= threshold;
    }

    /** Formatted percentage, e.g. "82.5%". */
    public String percent() {
        return String.format("%.1f%%", score * 100.0);
    }

    @Override
    public String toString() {
        return unmappedObfName + " -> " + targetOrigName + " @ " + percent() +
                " (m=" + matchedMethods + "/" + totalMethods +
                ", f=" + matchedFields + "/" + totalFields + ")";
    }
}
