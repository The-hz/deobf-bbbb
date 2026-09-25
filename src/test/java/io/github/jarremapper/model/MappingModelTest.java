package io.github.jarremapper.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MappingModel} — covers the P0-5 fix (ambiguous field
 * fallback returning the obf name instead of mispicking).
 */
class MappingModelTest {

    @Test
    void putAndLookupClass() {
        MappingModel m = new MappingModel();
        m.put("aaa", "com/example/Foo");
        assertEquals("com/example/Foo", m.mapClass("aaa"));
        // Unknown class returns obf name.
        assertEquals("xyz", m.mapClass("xyz"));
    }

    @Test
    void putAndLookupMethod() {
        MappingModel m = new MappingModel();
        m.putMethod("aaa", "com/example/Foo", "a", "()V", "doStuff");
        assertEquals("doStuff", m.mapMethod("aaa", "a", "()V"));
        // Unknown method on a known class → obf name.
        assertEquals("b", m.mapMethod("aaa", "b", "()I"));
    }

    @Test
    void putAndLookupField() {
        MappingModel m = new MappingModel();
        m.putField("aaa", "com/example/Foo", "a", "I", "value");
        assertEquals("value", m.mapField("aaa", "a", "I"));
    }

    @Test
    void fieldFallbackOnMissingDescriptor() {
        // SRG-style: field stored with placeholder descriptor "I" but the
        // applier queries with the real descriptor "Ljava/lang/String;".
        // The name-only fallback should find "value" because there's
        // exactly one field named "a" on the class.
        MappingModel m = new MappingModel();
        m.putField("aaa", "com/example/Foo", "a", "I", "value");
        assertEquals("value", m.mapField("aaa", "a", "Ljava/lang/String;"));
    }

    @Test
    void fieldAmbiguousFallbackBailsOut() {
        // P0-5 regression: two fields with the same name on the same class
        // (rare in practice but possible after merge). Should bail out
        // and return the obf name to avoid a wrong rename.
        MappingModel m = new MappingModel();
        m.putField("aaa", "com/example/Foo", "a", "I",                  "intValue");
        m.putField("aaa", "com/example/Foo", "a", "Ljava/lang/String;", "strValue");
        // Query with a third descriptor that matches neither.
        assertEquals("a", m.mapField("aaa", "a", "Ljava/lang/Object;"));
    }

    @Test
    void markIdentity() {
        MappingModel m = new MappingModel();
        var entry = m.markIdentity("aaa");
        assertEquals("aaa", entry.obfName());
        assertEquals("aaa", entry.origName());
        assertTrue(entry.identity());
        // mapClass on identity entry returns the obf name unchanged.
        assertEquals("aaa", m.mapClass("aaa"));
    }
}
