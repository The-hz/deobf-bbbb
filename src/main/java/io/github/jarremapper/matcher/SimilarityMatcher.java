package io.github.jarremapper.matcher;

import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.model.MatchResult;
import io.github.jarremapper.model.MemberKey;
import io.github.jarremapper.model.MethodInfo;
import io.github.jarremapper.model.TargetClassInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Computes pairwise similarity between an unmapped-JAR class and a target-JAR
 * class based on a weighted blend of:
 *
 * <ol>
 *   <li>Method set overlap — two methods are "the same" if they share
 *       <em>both</em> descriptor and bytecode structural fingerprint
 *       (so renaming doesn't change the score, but body-level
 *       instruction changes do).</li>
 *   <li>Field set overlap — Dice coefficient on the field descriptor
 *       multiset (field names are unreliable after obfuscation, but
 *       field types are stable).</li>
 *   <li>Method arity ratio — bonus when two classes have a similar number
 *       of methods (roughly within ±1).</li>
 *   <li>Field arity ratio — same for fields.</li>
 *   <li>Access-flag overlap — bitwise intersection of access flags.</li>
 *   <li>Super-class &amp; interface set overlap — only signals when
 *       the supers / interfaces are JDK classes (those survive
 *       obfuscation).</li>
 * </ol>
 *
 * <p>The final score is clamped to {@code [0, 1]} and reported as a
 * percentage by the GUI.</p>
 */
public class SimilarityMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SimilarityMatcher.class);

    // ----- weights -----
    private static final double W_METHOD_FP   = 0.45;
    private static final double W_FIELD_DESC   = 0.20;
    private static final double W_METHOD_DESC  = 0.10;
    private static final double W_ARITY       = 0.10;
    private static final double W_ACCESS      = 0.05;
    private static final double W_SUPERS      = 0.10;

    /**
     * Compare one unmapped-JAR class against every target-JAR class, return
     * the best-scoring pair (or empty if no target scored above
     * {@code threshold}).
     */
    public Optional<MatchResult> matchBest(ClassInfo unmapped,
                                          Collection<TargetClassInfo> targets,
                                          double threshold) {
        MatchResult best = null;
        for (TargetClassInfo t : targets) {
            MatchResult r = matchPair(unmapped, t);
            if (best == null || r.score() > best.score()) {
                best = r;
            }
        }
        if (best == null) return Optional.empty();
        return best.meets(threshold) ? Optional.of(best) : Optional.empty();
    }

    /** Match every unmapped class against {@code targets}; return one row per unmapped class. */
    public List<MatchResult> matchAll(Collection<ClassInfo> unmapped,
                                      Collection<TargetClassInfo> targets,
                                      double threshold) {
        List<MatchResult> out = new ArrayList<>(unmapped.size());
        for (ClassInfo u : unmapped) {
            Optional<MatchResult> best = matchBest(u, targets, threshold);
            out.add(best.orElseGet(() -> unmatchedResult(u)));
        }
        return out;
    }

    /** Pairwise score between an unmapped-JAR class and a target-JAR class. */
    public MatchResult matchPair(ClassInfo a, TargetClassInfo b) {
        // ----- method fingerprint sets -----
        // Element = (descriptor, fingerprint) — a "method identity".
        Map<String, Integer> aMethodSet = buildMethodSet(a);
        Map<String, Integer> bMethodSet = buildMethodSet(b.info());
        int aMethodCount = aMethodSet.size();
        int bMethodCount = bMethodSet.size();
        int matchedMethods = overlapSize(aMethodSet, bMethodSet);

        double methodFpDice = dice(aMethodCount, bMethodCount, matchedMethods);

        // ----- method descriptor multiset (drops the fingerprint) -----
        Map<String, Integer> aDesc = buildMethodDescMultiset(a);
        Map<String, Integer> bDesc = buildMethodDescMultiset(b.info());
        int aDescTotal = aDesc.values().stream().mapToInt(Integer::intValue).sum();
        int bDescTotal = bDesc.values().stream().mapToInt(Integer::intValue).sum();
        int descInter = overlapSize(aDesc, bDesc);
        double methodDescDice = dice(aDescTotal, bDescTotal, descInter);

        // ----- field descriptor multiset -----
        Map<String, Integer> aField = buildFieldMultiset(a);
        Map<String, Integer> bField = buildFieldMultiset(b.info());
        int aFieldTotal = aField.values().stream().mapToInt(Integer::intValue).sum();
        int bFieldTotal = bField.values().stream().mapToInt(Integer::intValue).sum();
        int fieldInter = overlapSize(aField, bField);
        double fieldDescDice = dice(aFieldTotal, bFieldTotal, fieldInter);

        // ----- arity ratio -----
        int am = a.methodCount(), bm = b.info().methodCount();
        int af = a.fieldCount(),  bf = b.info().fieldCount();
        double arityMethod = ratioScore(am, bm);
        double arityField  = ratioScore(af, bf);
        double arityScore = 0.5 * arityMethod + 0.5 * arityField;

        // ----- access flags overlap -----
        double accessScore = bitwiseOverlap(a.access(), b.info().access());

        // ----- super / interface overlap (only JDK refs) -----
        double superScore = superScore(a, b.info());

        double score = W_METHOD_FP * methodFpDice
                     + W_FIELD_DESC * fieldDescDice
                     + W_METHOD_DESC * methodDescDice
                     + W_ARITY       * arityScore
                     + W_ACCESS      * accessScore
                     + W_SUPERS      * superScore;

        // Clamp.
        if (score < 0) score = 0;
        if (score > 1) score = 1;

        return new MatchResult(
                a.internalName(),
                b.obfName(),
                b.origName(),
                score,
                matchedMethods,
                Math.max(aMethodCount, bMethodCount),
                fieldInter,
                Math.max(af, bf)
        );
    }

    /* ---------- helpers ---------- */

    private static MatchResult unmatchedResult(ClassInfo u) {
        return new MatchResult(u.internalName(), u.internalName(),
                u.internalName(), 0.0, 0, u.methodCount(), 0, u.fieldCount());
    }

    private static Map<String, Integer> buildMethodSet(ClassInfo c) {
        Map<String, Integer> set = new HashMap<>();
        for (MethodInfo m : c.methods().values()) {
            String key = m.descriptor() + "#" + Integer.toHexString(m.insnFingerprint());
            set.merge(key, 1, Integer::sum);
        }
        return set;
    }

    private static Map<String, Integer> buildMethodDescMultiset(ClassInfo c) {
        Map<String, Integer> set = new HashMap<>();
        for (MemberKey k : c.methods().keySet()) {
            set.merge(k.descriptor(), 1, Integer::sum);
        }
        return set;
    }

    private static Map<String, Integer> buildFieldMultiset(ClassInfo c) {
        Map<String, Integer> set = new HashMap<>();
        for (MemberKey k : c.fields().keySet()) {
            set.merge(k.descriptor(), 1, Integer::sum);
        }
        return set;
    }

    /** Multiset intersection size: sum of min(count_a, count_b). */
    private static int overlapSize(Map<String, Integer> a, Map<String, Integer> b) {
        int total = 0;
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            Integer bv = b.get(e.getKey());
            if (bv != null) {
                total += Math.min(e.getValue(), bv);
            }
        }
        return total;
    }

    /** Dice coefficient: 2 * inter / (|A| + |B|). Returns 0 for empty union. */
    private static double dice(int a, int b, int inter) {
        if (a + b == 0) return 1.0;
        return (2.0 * inter) / (a + b);
    }

    /** Score {@code min(|a/b|, |b/a|)} clamped to {@code [0, 1]}. */
    private static double ratioScore(int a, int b) {
        if (a == 0 && b == 0) return 1.0;
        if (a == 0 || b == 0) return 0.0;
        double r = (double) Math.min(a, b) / Math.max(a, b);
        return r;
    }

    private static double bitwiseOverlap(int a, int b) {
        int both = a & b;
        int either = a | b;
        if (either == 0) return 1.0;
        return Integer.bitCount(both) / (double) Integer.bitCount(either);
    }

    /**
     * Score the super-class and interface overlap. We only consider JDK /
     * well-known references that survive obfuscation (anything that starts
     * with {@code java/}, {@code javax/}, or that has no {@code $} and
     * doesn't appear in the obfuscated-named set — best-effort).
     */
    private static double superScore(ClassInfo a, ClassInfo b) {
        Set<String> aRefs = collectJdkRefs(a);
        Set<String> bRefs = collectJdkRefs(b);
        if (aRefs.isEmpty() && bRefs.isEmpty()) return 0.5; // unknown — neutral
        // Dice.
        int inter = 0;
        for (String r : aRefs) if (bRefs.contains(r)) inter++;
        int total = aRefs.size() + bRefs.size();
        if (total == 0) return 1.0;
        return (2.0 * inter) / total;
    }

    private static Set<String> collectJdkRefs(ClassInfo c) {
        Set<String> refs = new HashSet<>();
        if (c.superName() != null && isLikelyJdk(c.superName())) refs.add("S:" + c.superName());
        if (c.interfaces() != null) {
            for (String i : c.interfaces()) {
                if (isLikelyJdk(i)) refs.add("I:" + i);
            }
        }
        return refs;
    }

    private static boolean isLikelyJdk(String internal) {
        if (internal == null) return false;
        return internal.startsWith("java/") ||
               internal.startsWith("javax/") ||
               internal.startsWith("sun/") ||
               internal.startsWith("com/sun/") ||
               internal.startsWith("jdk/");
    }
}
