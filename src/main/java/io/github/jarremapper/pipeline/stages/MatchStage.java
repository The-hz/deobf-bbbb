package io.github.jarremapper.pipeline.stages;

import io.github.jarremapper.matcher.MinHashLsh;
import io.github.jarremapper.matcher.SimilarityMatcher;
import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.model.MatchResult;
import io.github.jarremapper.model.MemberKey;
import io.github.jarremapper.model.TargetClassInfo;
import io.github.jarremapper.pipeline.PipelineCallback;
import io.github.jarremapper.pipeline.PipelineContext;
import io.github.jarremapper.pipeline.PipelineStage;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Stage 3: compute pairwise similarity between every unmapped class and the
 * target classes that survived Stage 1's cross-join.
 *
 * <p><b>Performance characteristics.</b>
 * <ul>
 *   <li>Index the targets with {@link MinHashLsh} on their
 *       method-descriptor multisets — O(M * K) where K is the signature length.</li>
 *   <li>For each unmapped class, query the LSH for candidate target IDs —
 *       O(N * K) total.</li>
 *   <li>For each candidate pair, run {@link SimilarityMatcher#matchPair} —
 *       O(|candidate pairs| * method-set size).</li>
 *   <li>Pairwise {@code matchPair} calls are independent → run on
 *       {@code parallelStream()}.</li>
 * </ul>
 *
 * <p>The expected complexity is O(N + M + |candidate pairs|), down from
 * O(N * M) for naive brute-force.</p>
 *
 * <p><b>Sequential apply phase.</b> The match results are collected into a
 * {@link ConcurrentLinkedQueue} during the parallel phase, then drained
 * sequentially into the {@link io.github.jarremapper.model.MappingModel}
 * (which is NOT thread-safe) and the GUI's results table (via the
 * FX-thread-safe callback). Per-class progress is reported here too,
 * matching the original sequential semantics.</p>
 */
public final class MatchStage implements PipelineStage {

    @Override public String name() { return "Match by similarity"; }

    @Override
    public void run(PipelineContext ctx) throws Exception {
        PipelineCallback cb = ctx.callback;
        cb.onProgress(0.25, name());

        // ----- LSH pre-filter on target method-descriptor multisets -----
        // 128 hashes / 32 bands × 4 rows is the classic tradeoff:
        // ~30% Jaccard threshold to land in the same band, which is a
        // reasonable lower bound for "might be the same source class".
        MinHashLsh<Integer> lsh = new MinHashLsh<>(128, 32);
        List<TargetClassInfo> targets = ctx.targets;
        for (int i = 0; i < targets.size(); i++) {
            lsh.index(i, methodDescriptorSet(targets.get(i).info()));
        }
        cb.onLog("LSH index: " + lsh.indexedCount() + " target entries across " +
                lsh.bucketCount() + " buckets.");

        // ----- Parallel phase: compute matches -----
        // We parallelize the per-unmapped-class matching; each iteration:
        //   1. Query LSH for candidate target IDs.
        //   2. Also apply the cheap arity pre-filter (within 2x) on candidates.
        //   3. Run matchPair on each surviving candidate, pick the best.
        //   4. Push (unmapped, result) into a thread-safe queue.
        List<ClassInfo> unmappedList = new ArrayList<>(ctx.unmappedParsed.values());
        ConcurrentLinkedQueue<Map.Entry<ClassInfo, Optional<MatchResult>>> resultQueue =
                new ConcurrentLinkedQueue<>();

        SimilarityMatcher matcher = ctx.matcher;
        double threshold = ctx.threshold();

        // Save current parallelism threshold in case the user has none set.
        // (The FJ pool auto-sizes to the number of CPU cores.)
        unmappedList.parallelStream().forEach(u -> {
            if (cb.isCancelled()) {
                // Throwing from a parallel-stream lambda is messy; instead
                // we just early-out and the post-loop check below will
                // throw InterruptedException cleanly.
                return;
            }
            // LSH candidate query.
            Set<Integer> candidateIds = lsh.query(methodDescriptorSet(u));
            // Sequentially run matchPair on candidates, applying the arity
            // pre-filter on each.
            MatchResult best = null;
            for (Integer id : candidateIds) {
                TargetClassInfo t = targets.get(id);
                // Cheap pre-filter: skip grossly-different arity.
                if (!within2x(u.methodCount(), t.info().methodCount())) continue;
                if (!within2x(u.fieldCount(),  t.info().fieldCount()))  continue;
                MatchResult r = matcher.matchPair(u, t);
                if (best == null || r.score() > best.score()) best = r;
            }
            // If LSH found no candidates, fall back to scanning all targets
            // (rare — happens only when the unmapped class is very
            // dissimilar to every target class).
            if (best == null) {
                for (TargetClassInfo t : targets) {
                    if (!within2x(u.methodCount(), t.info().methodCount())) continue;
                    if (!within2x(u.fieldCount(),  t.info().fieldCount()))  continue;
                    MatchResult r = matcher.matchPair(u, t);
                    if (best == null || r.score() > best.score()) best = r;
                }
            }
            Optional<MatchResult> opt = (best == null || !best.meets(threshold))
                    ? Optional.empty() : Optional.of(best);
            resultQueue.add(new AbstractMap.SimpleImmutableEntry<>(u, opt));
        });

        if (cb.isCancelled()) throw new InterruptedException("User cancelled.");

        // ----- Sequential apply phase -----
        int total = unmappedList.size();
        int matchedCount = 0;
        int idx = 0;
        // Drain the queue — order is non-deterministic but the GUI table
        // is the only consumer, and we'd rather get faster results than
        // preserve input order.
        for (var entry : resultQueue) {
            idx++;
            cb.onProgress(0.25 + 0.20 * (idx / (double) Math.max(1, total)),
                    "Applying match " + idx + "/" + total + ": " +
                            entry.getKey().internalName());
            ClassInfo u = entry.getKey();
            Optional<MatchResult> opt = entry.getValue();
            if (opt.isPresent()) {
                MatchResult r = opt.get();
                applyClassMatch(u, ctx.targetParsed, r, ctx.outMapping, ctx.targetMapping);
                matchedCount++;
                cb.onMatchResult(r);
            } else {
                MatchResult r = new MatchResult(u.internalName(), u.internalName(),
                        u.internalName(), 0.0, 0, u.methodCount(), 0, u.fieldCount());
                cb.onMatchResult(r);
                ctx.unmatched.add(u);
            }
        }
        cb.onLog("Matched " + matchedCount + " classes; " + ctx.unmatched.size() +
                " unmatched (queued for review).");
    }

    /**
     * Apply a successful match: write class + method + field mappings from
     * the target's mapping into the out-model keyed by the unmapped class.
     * Lifted from the original {@code RemapPipeline.applyClassMatch}.
     */
    private void applyClassMatch(ClassInfo unmapped,
                                  Map<String, ClassInfo> targetParsed,
                                  MatchResult r,
                                  io.github.jarremapper.model.MappingModel outMapping,
                                  io.github.jarremapper.model.MappingModel targetMapping) {
        outMapping.put(r.unmappedObfName(), r.targetOrigName());
        ClassInfo tInfo = targetParsed.get(r.targetObfName());
        io.github.jarremapper.model.MappingModel.ClassEntry tEntry = targetMapping.get(r.targetObfName());
        if (tInfo == null || tEntry == null) return;

        // Method map: (descriptor, fingerprint) → orig name.
        java.util.Map<String, String> methodFpToOrig = new java.util.HashMap<>();
        for (var e : tInfo.methods().entrySet()) {
            String fpKey = e.getKey().descriptor() + "#" +
                    Integer.toHexString(e.getValue().insnFingerprint());
            String orig = tEntry.methods().get(e.getKey());
            if (orig == null) orig = e.getKey().name();
            methodFpToOrig.put(fpKey, orig);
        }

        for (var me : unmapped.methods().entrySet()) {
            MemberKey mk = me.getKey();
            String fpKey = mk.descriptor() + "#" +
                    Integer.toHexString(me.getValue().insnFingerprint());
            String orig = methodFpToOrig.get(fpKey);
            if (orig == null) orig = mk.name();
            outMapping.putMethod(r.unmappedObfName(), r.targetOrigName(),
                    mk.name(), mk.descriptor(), orig);
        }

        // Field map (P0-1 fix): per-descriptor FIFO with multi-signal
        // disambiguation (desc + access + initFp).
        java.util.Map<String, java.util.Deque<FieldCandidate>> fieldCandidatesByDesc =
                new java.util.HashMap<>();
        for (var e : tEntry.fields().entrySet()) {
            String desc = e.getKey().descriptor();
            fieldCandidatesByDesc.computeIfAbsent(desc, k -> new java.util.ArrayDeque<>())
                    .add(new FieldCandidate(e.getValue(),
                            lookupFieldAccess(tInfo, e.getKey()),
                            lookupFieldInitFp(tInfo, e.getKey())));
        }
        for (MemberKey mk : unmapped.fields().keySet()) {
            java.util.Deque<FieldCandidate> q = fieldCandidatesByDesc.get(mk.descriptor());
            String orig = null;
            if (q != null && !q.isEmpty()) {
                FieldCandidate best = null;
                int uAccess = lookupUnmappedFieldAccess(unmapped, mk);
                int uInitFp = lookupUnmappedFieldInitFp(unmapped, mk);
                for (FieldCandidate c : q) {
                    if (c.access == uAccess && c.initFp == uInitFp && uAccess != 0) {
                        best = c; break;
                    }
                }
                if (best == null) {
                    int uAccess2 = lookupUnmappedFieldAccess(unmapped, mk);
                    for (FieldCandidate c : q) {
                        if (c.access != 0 && c.access == uAccess2) {
                            best = c; break;
                        }
                    }
                }
                if (best == null) best = q.peek();
                q.remove(best);
                orig = best.origName;
            }
            if (orig == null) orig = mk.name();
            outMapping.putField(r.unmappedObfName(), r.targetOrigName(),
                    mk.name(), mk.descriptor(), orig);
        }
    }

    /** Tiny struct used by field disambiguation. */
    private record FieldCandidate(String origName, int access, int initFp) {}

    private static int lookupFieldAccess(ClassInfo tInfo, MemberKey key) {
        if (tInfo == null) return 0;
        io.github.jarremapper.model.FieldInfo fi = tInfo.fields().get(key);
        return fi == null ? 0 : fi.access();
    }

    private static int lookupFieldInitFp(ClassInfo tInfo, MemberKey key) {
        if (tInfo == null) return 0;
        io.github.jarremapper.model.FieldInfo fi = tInfo.fields().get(key);
        return fi == null ? 0 : fi.initFingerprint();
    }

    private static int lookupUnmappedFieldAccess(ClassInfo unmapped, MemberKey key) {
        io.github.jarremapper.model.FieldInfo fi = unmapped.fields().get(key);
        return fi == null ? 0 : fi.access();
    }

    private static int lookupUnmappedFieldInitFp(ClassInfo unmapped, MemberKey key) {
        io.github.jarremapper.model.FieldInfo fi = unmapped.fields().get(key);
        return fi == null ? 0 : fi.initFingerprint();
    }

    /** Build the set of method descriptors for a class — input to LSH. */
    private static Set<String> methodDescriptorSet(ClassInfo ci) {
        Set<String> set = new HashSet<>();
        for (MemberKey k : ci.methods().keySet()) set.add(k.descriptor());
        return set;
    }

    /** Returns true if a and b are within 2× of each other (or both zero). */
    private static boolean within2x(int a, int b) {
        if (a == 0 && b == 0) return true;
        if (a == 0 || b == 0) return false;
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return lo * 2 >= hi;
    }
}
