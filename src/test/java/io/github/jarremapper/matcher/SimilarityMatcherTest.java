package io.github.jarremapper.matcher;

import io.github.jarremapper.model.ClassInfo;
import io.github.jarremapper.model.MatchResult;
import io.github.jarremapper.model.MemberKey;
import io.github.jarremapper.model.MethodInfo;
import io.github.jarremapper.model.TargetClassInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SimilarityMatcher} — covers self-match (100%) and the
 * bucket pre-filter (P0-3).
 */
class SimilarityMatcherTest {

    @Test
    void identicalClassMatchesAt100Percent() {
        ClassInfo probe = makeClass("org/test/Foo",
                new MethodInfo(MemberKey.of("a", "()V"), 1, 0x1111, 0x2222, 1, 0, 5),
                new MethodInfo(MemberKey.of("b", "()I"), 1, 0x3333, 0x4444, 1, 0, 3));
        TargetClassInfo target = new TargetClassInfo(probe, "com/example/Foo_ORIG");

        SimilarityMatcher matcher = new SimilarityMatcher();
        MatchResult r = matcher.matchPair(probe, target);
        assertEquals(1.0, r.score(), 1e-9, "self-match should score 100%");
        assertEquals(2, r.matchedMethods(), "both methods should match");
    }

    @Test
    void bucketPreFilterStillFindsSelfMatch() {
        // When we have many candidate targets with different arities, the
        // bucket pre-filter must NOT exclude a self-match (which has the
        // same arity, of course).
        ClassInfo probe = makeClass("org/test/Foo",
                new MethodInfo(MemberKey.of("a", "()V"), 1, 0x1111, 0x2222, 1, 0, 5));

        // Targets: one with same arity, several with very different arity
        // (those should be filtered out).
        TargetClassInfo sameArity = new TargetClassInfo(probe, "com/example/Foo_ORIG");
        ClassInfo big = makeClass("org/test/Big",
                new MethodInfo(MemberKey.of("a", "()V"), 1, 0x1111, 0x2222, 1, 0, 5),
                new MethodInfo(MemberKey.of("b", "()V"), 1, 0x1112, 0x2223, 1, 0, 5),
                new MethodInfo(MemberKey.of("c", "()V"), 1, 0x1113, 0x2224, 1, 0, 5),
                new MethodInfo(MemberKey.of("d", "()V"), 1, 0x1114, 0x2225, 1, 0, 5),
                new MethodInfo(MemberKey.of("e", "()V"), 1, 0x1115, 0x2226, 1, 0, 5));
        TargetClassInfo huge = new TargetClassInfo(big, "com/example/Big_ORIG");

        SimilarityMatcher matcher = new SimilarityMatcher();
        List<MatchResult> results = matcher.matchAll(
                List.of(probe), List.of(sameArity, huge), 0.0);
        assertEquals(1, results.size());
        // Best score should be 1.0 (self-match), since huge would have
        // a different arity and is filtered out anyway.
        assertTrue(results.get(0).score() > 0.9,
                "Self-match should still be found despite bucket filtering");
        assertEquals("com/example/Foo_ORIG", results.get(0).targetOrigName());
    }

    @Test
    void noMatchBelowThresholdReturnsZeroRow() {
        // Two unrelated classes — they shouldn't match above threshold 0.7.
        ClassInfo probe = makeClass("org/test/A",
                new MethodInfo(MemberKey.of("a", "()V"), 1, 0xAAAA, 0xBBBB, 1, 0, 3));
        ClassInfo other = makeClass("org/test/B",
                new MethodInfo(MemberKey.of("b", "()V"), 1, 0xCCCC, 0xDDDD, 1, 0, 7));
        TargetClassInfo otherT = new TargetClassInfo(other, "com/example/B_ORIG");

        SimilarityMatcher matcher = new SimilarityMatcher();
        Optional<MatchResult> best = matcher.matchBest(probe, List.of(otherT), 0.7);
        assertTrue(best.isEmpty(), "unrelated classes shouldn't match above 0.7");
    }

    /** Build a ClassInfo with the given methods (and no fields, super=Object). */
    private static ClassInfo makeClass(String name, MethodInfo... methods) {
        ClassInfo ci = new ClassInfo(name);
        ci.setAccess(0x0001);   // public
        ci.setSuperName("java/lang/Object");
        ci.setInterfaces(new String[0]);
        for (MethodInfo m : methods) ci.addMethod(m);
        return ci;
    }
}
