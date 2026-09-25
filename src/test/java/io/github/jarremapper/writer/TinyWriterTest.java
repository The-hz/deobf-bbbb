package io.github.jarremapper.writer;

import io.github.jarremapper.model.MappingModel;
import io.github.jarremapper.parser.MappingParsers;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip test: parse a Tiny v2 file, write it back, parse again, and
 * verify the model is identical.
 */
class TinyWriterTest {

    @Test
    void roundTripPreservesModel() throws Exception {
        String input =
                "tiny\t2\t0\tobfuscated\tnamed\n" +
                "c\taaa\tcom/example/Foo\n" +
                "\tm\ta\t()V\tdoStuff\n" +
                "\tm\tb\t()I\tgetCount\n" +
                "\tf\ta\tI\tvalue\n" +
                "c\taab\tcom/example/Bar\n" +
                "\tf\ta\tLjava/lang/String;\tname\n";
        Path in = Files.createTempFile("rt-in-", ".tiny");
        Files.writeString(in, input);
        MappingModel original = MappingParsers.parse(in);

        Path out = Files.createTempFile("rt-out-", ".tiny");
        new TinyWriter(original).write(out);

        // Parse the written file back and verify.
        MappingModel roundTripped = MappingParsers.parse(out);

        assertEquals(original.size(), roundTripped.size());
        for (var entry : original.entries()) {
            var rtEntry = roundTripped.get(entry.obfName());
            assertNotNull(rtEntry, "missing class " + entry.obfName());
            assertEquals(entry.origName(), rtEntry.origName());
            assertEquals(entry.methods().size(), rtEntry.methods().size());
            assertEquals(entry.fields().size(),  rtEntry.fields().size());

            for (var me : entry.methods().entrySet()) {
                String rtOrig = rtEntry.methods().get(me.getKey());
                assertEquals(me.getValue(), rtOrig,
                        "method " + me.getKey() + " orig mismatch");
            }
            for (var fe : entry.fields().entrySet()) {
                String rtOrig = rtEntry.fields().get(fe.getKey());
                assertEquals(fe.getValue(), rtOrig,
                        "field " + fe.getKey() + " orig mismatch");
            }
        }
    }

    @Test
    void writesHeaderWithVersionAndNamespaces() throws Exception {
        MappingModel m = new MappingModel();
        m.setNamespaces("obfuscated", "named");
        m.put("aaa", "com/example/Foo");

        Path out = Files.createTempFile("hdr-out-", ".tiny");
        new TinyWriter(m).write(out);

        String firstLine = Files.readAllLines(out).get(0);
        assertEquals("tiny\t2\t0\tobfuscated\tnamed", firstLine);
    }

    @Test
    void writesIdentityEntriesVerbatim() throws Exception {
        // User requirement: skipped classes get identity mapping (aaa -> aaa).
        MappingModel m = new MappingModel();
        m.markIdentity("aaa");
        Path out = Files.createTempFile("ident-out-", ".tiny");
        new TinyWriter(m).write(out);

        var lines = Files.readAllLines(out);
        // Should contain: tiny header, then "c\taaa\taaa".
        assertTrue(lines.stream().anyMatch(l -> l.equals("c\taaa\taaa")),
                "Identity entry not found in output: " + lines);
    }

    @Test
    void escapesTabsInNames() throws Exception {
        MappingModel m = new MappingModel();
        m.put("with\ttab", "orig\tname");
        Path out = Files.createTempFile("esc-out-", ".tiny");
        new TinyWriter(m).write(out);

        // The tab in the name should be escaped to "\t" (backslash + t)
        // so the line still has exactly one tab separator per column.
        var lines = Files.readAllLines(out);
        // Expected line: c<TAB>with\ ttab<TAB>orig\ tname (where \t = BACKSLASH+t)
        assertTrue(lines.stream().anyMatch(l ->
                l.equals("c\twith\\ttab\torig\\tname")),
                "Escaped entry not found in output: " + lines);
    }
}
