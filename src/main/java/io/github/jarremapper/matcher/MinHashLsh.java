package io.github.jarremapper.matcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MinHash + LSH (Locality-Sensitive Hashing) for fast "find similar sets"
 * queries.
 *
 * <p>Background: computing the pairwise Jaccard similarity of N unmapped vs M
 * target class method-descriptor multisets is O(N*M*|set|). MinHash lets us
 * approximate the Jaccard with constant-time signatures of length K; LSH
 * banding then buckets the signatures so that only pairs sharing at least
 * one band-hash need to be compared exactly. The expected complexity drops
 * from O(N*M) to O(N + M + |candidate pairs|).</p>
 *
 * <p>Reference: Broder 1997 "On the Resemblance and Containment of Documents".</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MinHashLsh<Integer> lsh = new MinHashLsh<>(128, 4);
 * // Index target items
 * for (var t : targets) {
 *     lsh.index(t.id(), t.methodDescriptorSet());
 * }
 * // Query: find candidate target ids that might match this unmapped set
 * Set<Integer> candidateIds = lsh.query(unmapped.methodDescriptorSet());
 * // Then do exact pairwise comparison only against candidates.
 * }</pre>
 *
 * <p>This class is not thread-safe — index all items first, then query
 * (or use one instance per thread).</p>
 *
 * @param <K> the identifier type used to refer to indexed sets (typically
 *           a class ID or an integer index).
 */
public final class MinHashLsh<K> {

    /** Number of MinHash functions (signature length). Higher = more precise. */
    private final int numHashes;
    /** Number of bands (LSH parameter). Higher = more candidates found. */
    private final int numBands;
    /** Rows per band. {@code numHashes == numBands * rowsPerBand} must hold. */
    private final int rowsPerBand;

    /** Random seeds for the K hash functions. */
    private final int[] seeds;

    /**
     * Per-band buckets: {@code bands[b].get(bandHash) = [id1, id2, ...]}.
     * Built up as items are indexed.
     */
    private final Map<Integer, List<K>>[] bands;

    /**
     * Construct with {@code numHashes} MinHash functions and {@code numBands}
     * LSH bands. {@code numHashes} must be a positive multiple of
     * {@code numBands}.
     *
     * @param numHashes signature length (e.g. 128)
     * @param numBands  LSH bands (e.g. 32 → rowsPerBand = 4)
     */
    @SuppressWarnings("unchecked")
    public MinHashLsh(int numHashes, int numBands) {
        if (numHashes <= 0 || numBands <= 0 || numHashes % numBands != 0) {
            throw new IllegalArgumentException(
                    "numHashes (" + numHashes + ") must be a positive multiple of numBands (" + numBands + ")");
        }
        this.numHashes = numHashes;
        this.numBands = numBands;
        this.rowsPerBand = numHashes / numBands;
        // Deterministic seeds (so re-runs are reproducible). Uses a
        // Linear Congruential Generator — good enough spread for our use.
        this.seeds = new int[numHashes];
        long state = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < numHashes; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            this.seeds[i] = (int) (state >>> 32);
        }
        this.bands = (Map<Integer, List<K>>[]) java.lang.reflect.Array.newInstance(
                Map.class, numBands);
        for (int b = 0; b < numBands; b++) bands[b] = new HashMap<>();
    }

    /**
     * Compute the MinHash signature of a set of strings.
     *
     * <p>For each of the {@code numHashes} hash functions, the signature
     * element is {@code min(h_i(x) for x in set)} where {@code h_i(x) =
     * x.hashCode() XOR seeds[i]}. Empty sets get a zero signature.</p>
     */
    public int[] signature(Set<String> set) {
        int[] sig = new int[numHashes];
        if (set.isEmpty()) {
            return sig;     // all zeros — empty sets match nothing
        }
        java.util.Arrays.fill(sig, Integer.MAX_VALUE);
        for (String s : set) {
            int base = s.hashCode();
            for (int i = 0; i < numHashes; i++) {
                int h = base ^ seeds[i];
                if (h < sig[i]) sig[i] = h;
            }
        }
        return sig;
    }

    /** Index an item under the LSH bands derived from its MinHash signature. */
    public void index(K id, Set<String> set) {
        int[] sig = signature(set);
        for (int b = 0; b < numBands; b++) {
            int bandHash = bandHash(sig, b);
            bands[b].computeIfAbsent(bandHash, k -> new ArrayList<>()).add(id);
        }
    }

    /**
     * Query the index for items that share at least one band-hash with the
     * given set's signature. The result is a {@link Set} (deduped).
     *
     * <p>This is the candidate set for the LSH pre-filter — you then run
     * exact pairwise comparison against only these items instead of against
     * the entire index.</p>
     */
    public Set<K> query(Set<String> set) {
        int[] sig = signature(set);
        Set<K> out = new HashSet<>();
        for (int b = 0; b < numBands; b++) {
            int bandHash = bandHash(sig, b);
            List<K> bucket = bands[b].get(bandHash);
            if (bucket != null) out.addAll(bucket);
        }
        return out;
    }

    /** Number of items indexed so far (across all bands; dedup not applied). */
    public int indexedCount() {
        int sum = 0;
        for (var b : bands) sum += b.values().stream().mapToInt(List::size).sum();
        return sum;
    }

    /** Number of distinct buckets across all bands — diagnostic only. */
    public int bucketCount() {
        int sum = 0;
        for (var b : bands) sum += b.size();
        return sum;
    }

    /** Hash the {@code rowsPerBand} signature elements of band {@code b}. */
    private int bandHash(int[] sig, int b) {
        int start = b * rowsPerBand;
        return Arrays.hashCode(
                Arrays.copyOfRange(sig, start, start + rowsPerBand));
    }
}
