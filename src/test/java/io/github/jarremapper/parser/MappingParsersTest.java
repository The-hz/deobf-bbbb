package io.github.jarremapper.parser;

import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.parser.impl.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MappingParsers} auto-detection and the 6 format-specific
 * parsers (TinyV1, TinyV2, TsrgV1, TsrgV2, Srg, Mojang).
 */
class MappingParsersTest {

    /** Helper: write content to a temp file and parse it. */
    private static MappingModel parse(String content) throws Exception {
        Path file = Files.createTempFile("test-mapping-", ".txt");
        Files.writeString(file, content);
        return MappingParsers.parse(file);
    }

    // ---- Tiny v2 ----

    @Test
    void detectsAndParsesTinyV2() throws Exception {
        String content =
                "tiny\t2\t0\tobfuscated\tnamed\n" +
                "c\taaa\tcom/example/Foo\n" +
                "\tm\ta\t()V\tdoStuff\n" +
                "\tf\ta\tI\tvalue\n" +
                "c\taab\tcom/example/Bar\n";
        MappingModel m = parse(content);
        assertEquals(2, m.size());
        assertEquals("com/example/Foo", m.mapClass("aaa"));
        assertEquals("com/example/Bar", m.mapClass("aab"));
        assertEquals("doStuff", m.mapMethod("aaa", "a", "()V"));
        assertEquals("value",  m.mapField ("aaa", "a", "I"));
    }

    @Test
    void tinyV2WithPrimitiveArrayDescriptors() throws Exception {
        // Regression for the bug where looksLikeDesc("I") returned false →
        // field orig name was misaligned with the descriptor.
        String content =
                "tiny\t2\t0\tobfuscated\tnamed\n" +
                "c\taaa\tcom/example/Foo\n" +
                "\tf\ta\tI\tintValue\n" +
                "\tf\tb\t[Ljava/lang/String;\tstrArray\n" +
                "\tf\tc\tJ\tlongValue\n";
        MappingModel m = parse(content);
        assertEquals("intValue",  m.mapField("aaa", "a", "I"));
        assertEquals("strArray",  m.mapField("aaa", "b", "[Ljava/lang/String;"));
        assertEquals("longValue", m.mapField("aaa", "c", "J"));
    }

    // ---- Tiny v1 ----

    @Test
    void detectsAndParsesTinyV1() throws Exception {
        String content =
                "tiny\t1\tobfuscated\tnamed\n" +
                "aaa\tcom/example/Foo\n" +
                "\ta\tdoStuff\t()V\n" +
                "\tb\tgetValue\t()I\n";
        MappingModel m = parse(content);
        assertEquals(1, m.size());
        assertEquals("com/example/Foo", m.mapClass("aaa"));
        assertEquals("doStuff",   m.mapMethod("aaa", "a", "()V"));
        assertEquals("getValue",  m.mapMethod("aaa", "b", "()I"));
    }

    // ---- TSRG v2 ----

    @Test
    void detectsAndParsesTsrgV2() throws Exception {
        String content =
                "tsrg2\t2\tobfuscated\tnamed\n" +
                "c\taaa\tcom/example/Foo\n" +
                "\tm\ta\t0\tdoStuff\t()V\n" +
                "\tf\ta\t0\tvalue\tI\n";
        MappingModel m = parse(content);
        assertEquals(1, m.size());
        assertEquals("com/example/Foo", m.mapClass("aaa"));
        assertEquals("doStuff", m.mapMethod("aaa", "a", "()V"));
        assertEquals("value",   m.mapField ("aaa", "a", "I"));
    }

    // ---- TSRG v1 ----

    @Test
    void detectsAndParsesTsrgV1() throws Exception {
        String content =
                "aaa\tcom/example/Foo\n" +
                "\ta\tdoStuff\t()V\n" +
                "\tb\tgetValue\t()I\n";
        MappingModel m = parse(content);
        assertEquals(1, m.size());
        assertEquals("com/example/Foo", m.mapClass("aaa"));
        assertEquals("doStuff",   m.mapMethod("aaa", "a", "()V"));
        assertEquals("getValue",  m.mapMethod("aaa", "b", "()I"));
    }

    // ---- SRG ----

    @Test
    void detectsAndParsesSrg() throws Exception {
        String content =
                "CL: aaa com/example/Foo\n" +
                "MD: aaa/a ()V com/example/Foo/doStuff ()V\n" +
                "FD: aaa/b com/example/Foo/value\n";
        MappingModel m = parse(content);
        assertEquals(1, m.size());
        assertEquals("com/example/Foo", m.mapClass("aaa"));
        assertEquals("doStuff", m.mapMethod("aaa", "a", "()V"));
        // SRG FD has no descriptor — model stores "I" placeholder but the
        // name-only fallback should still find "value".
        assertEquals("value", m.mapField("aaa", "b", "Ljava/lang/String;"));
    }

    // ---- Mojang (Proguard) ----

    @Test
    void detectsAndParsesMojang() throws Exception {
        String content =
                "# Mojang mappings\n" +
                "com.example.Qux -> aad:\n" +
                "    int counter -> a\n" +
                "    void run(java.lang.String,int) -> b\n";
        MappingModel m = parse(content);
        assertEquals(1, m.size());
        assertEquals("com/example/Qux", m.mapClass("aad"));
        assertEquals("counter", m.mapField("aad", "a", "I"));
        assertEquals("run", m.mapMethod("aad", "b", "(Ljava/lang/String;I)V"));
    }

    @Test
    void mojangHandlesArraysAndGenerics() throws Exception {
        String content =
                "com.example.Holder -> aae:\n" +
                "    java.util.Map<java.lang.String,java.lang.Integer> entries -> a\n" +
                "    byte[] buffer -> b\n";
        MappingModel m = parse(content);
        assertEquals("com/example/Holder", m.mapClass("aae"));
        // Generic stripped → Ljava/util/Map;
        assertEquals("entries", m.mapField("aae", "a", "Ljava/util/Map;"));
        assertEquals("buffer",  m.mapField("aae", "b", "[B"));
    }

    @Test
    void mojangStripsLineRangePrefix() throws Exception {
        String content =
                "com.example.WithLineRanges -> aaf:\n" +
                "    1:3:void compute(int) -> a\n" +
                "    4:5:boolean check() -> b\n";
        MappingModel m = parse(content);
        assertEquals("com/example/WithLineRanges", m.mapClass("aaf"));
        assertEquals("compute", m.mapMethod("aaf", "a", "(I)V"));
        assertEquals("check",  m.mapMethod("aaf", "b", "()Z"));
    }

    // ---- Format detection edge cases ----

    @Test
    void throwsOnUnrecognizedFormat() throws Exception {
        Path file = Path.of("/tmp/unknown-mapping-format-" + System.nanoTime() + ".txt");
        try {
            Files.writeString(file, "this is not a mapping file at all");
            Exception ex = assertThrows(IllegalArgumentException.class,
                    () -> MappingParsers.parse(file));
            assertTrue(ex.getMessage().contains("Could not auto-detect mapping format"));
        } finally {
            try { Files.deleteIfExists(file); } catch (Exception ignored) {}
        }
    }
}
