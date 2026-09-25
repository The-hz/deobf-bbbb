# JarRemapper

A JavaFX desktop tool that **transfers obfuscation mappings from one JAR to another** using **bytecode-similarity matching** + embedded decompiler (CFR / Fernflower / Vineflower) for the residual unmapped items.

* JDK 21 · JavaFX 21 · ASM 9.7
* License: **GPL-3.0-only**
* Build: Gradle (Groovy DSL)

## High-Level Pipeline

```
                          ┌───────────────────────────────┐
                          │  (1) target JAR (obfuscated)  │
                          │      + existing mapping file  │  ──► parse → "ground truth"
                          │      (.tiny/.srg/.tsrg/.mojang)│           obf→orig pairs
                          └───────────────────────────────┘

                          ┌───────────────────────────────┐
                          │  (2) unmapped JAR (obfuscated) │  ──► parse → obf-class tree
                          └───────────────────────────────┘

  (3) For each obfuscated class in (2), find the best-matching class in (1)
      by weighted similarity  =  signature + bytecode structural hash.
      If similarity >= user threshold, create mapping:  unmapped-obf → target-orig-name

  (4) For remaining unmapped classes/methods/fields:
      ── embedded decompiler (default CFR, switchable) writes the source
      ── user picks a custom name, OR clicks "Skip"
         ── if skipped, an identity mapping entry is created (orig → orig)

  (5) Apply the assembled mapping to JAR (2), producing the *remapped* JAR.

  (6) Decompile the remapped JAR → readable Java sources.

  (7) Serialize the assembled mapping as Tiny v2 into ./output/mappings.tiny
      Write decompiled sources to ./output/sources/
      Write remapped JAR       to ./output/remapped.jar
```

## Directory Layout

```
jarremapper/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── LICENSE                         (GPL-3.0)
├── README.md
└── src/main/
    ├── java/io/github/jarremapper/
    │   ├── Main.java                          // JavaFX entry point
    │   ├── config/AppConfig.java              // user-tunable settings
    │   ├── model/                             // MappingModel / ClassInfo / ...
    │   ├── parser/                            // JAR + 4 mapping formats
    │   ├── matcher/                           // similarity engine
    │   ├── decompiler/                        // CFR / Fernflower / Vineflower adapters
    │   ├── applier/MappingApplier.java        // ASM Remapper-based
    │   ├── writer/TinyWriter.java             // Tiny v2 emitter
    │   ├── pipeline/RemapPipeline.java        // orchestration
    │   ├── util/                               // helpers
    │   └── ui/                                 // FXML + Controller + CSS
    └── resources/io/github/jarremapper/ui/
        ├── AppView.fxml
        └── styles.css
```

## Build & Run

```bash
# from project root
./gradlew guiRun
# or
./gradlew run
```

If no Gradle wrapper jar is present, run with a system-installed Gradle 8.5+:

```bash
gradle guiRun
```

## Drag-and-Drop UX

The window has **three drop zones** side-by-side:

| Drop zone #1 | Drop zone #2 | Drop zone #3 |
|---|---|---|
| Target JAR (already mapped, obfuscated) | Existing mapping file (tiny/srg/tsrg/mojang auto-detect) | Unmapped JAR (obfuscated, no mapping) |

Below the drop zones:

* **Similarity threshold** slider (0%–100%) — only matches ≥ threshold produce a mapping entry.
* **Decompiler** combo box — `CFR` (default) / `Fernflower` / `Vineflower`.
* **Run** button — starts the pipeline; progress bar + log table fill in.
* Per-class review dialog: shows the decompiled source side-by-side; user picks a name or "Skip".

## Output

Everything lands under `./output/`:

```
output/
├── sources/                       # .java files of the remapped JAR
├── remapped.jar                   # the JAR after the new mapping has been applied
└── mappings.tiny                  # the assembled mapping (Tiny v2 format)
```

Example `mappings.tiny`:

```
tiny    2       0       obfuscated      named
c       aaa     com/example/Foo
        m       a       (Ljava/lang/String;)V   doStuff
        m       b       ()I     getCount
        f       a       I       value
c       aab     com/example/Bar
        m       a       ()V     plainIdentityMethod
```

## Mapping File Format Support

| Format | Detected by | Notes |
|---|---|---|
| Tiny v2 | `tiny\t2\t0` header | official Fabric format |
| Tiny v1 | `tiny\t1\t` header | legacy |
| TSRG v2 | `tsrg2\t` first line | Mojang's tabbed format |
| TSRG v1 | leading `\t` lines, no `tsrg2` marker | legacy MCP |
| SRG (notch/srg/mcp) | lines starting with `CL:`, `FD:`, `MD:` | classic MCP |
| Mojang (`proguard` mappings) | `# mapping file` header | provided by Mojang since 1.14.4 |

## Decompiler Notes

* **CFR** — `org.benf:cfr:0.152` (Maven Central). Default.
* **Fernflower** — there is no official JetBrains Fernflower artifact on Maven Central.
  The build pulls in `org.quiltmc:quiltflower:1.9.0` (a community-maintained fork
  of Fernflower that lives on Maven Central and exposes the same
  `org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler` main class).
  If you want the very latest JetBrains Fernflower (newer than Quiltflower),
  drop its jar into `./lib/fernflower.jar` and uncomment the
  `fileTree(dir: 'lib', include: ['fernflower*.jar'])` line in `build.gradle`.
* **Vineflower** — `org.vineflower:vineflower:1.10.1` (Maven Central) — the
  actively-maintained successor to Quiltflower.

All three adapters share the same API:

```java
interface Decompiler {
    String name();
    void decompile(Path jarOrClass, Path outputDir, Map<String,String> options);
}
```

The adapters spawn a child JVM with `-cp` set to **only** the decompiler's
own jar — this isolates Fernflower / Vineflower (which share the
`org.jetbrains.java.decompiler` package) from each other and shields the
GUI process from any `System.exit()` the decompiler may invoke.

## Verified Build & Smoke Tests

The source tree has been syntax-checked with JDK 21 (`javac --release 21`) and
end-to-end smoke-tested against the ASM 9.7 jar:

* `MappingParsers` correctly auto-detects and parses: Tiny v2, Tiny v1, TSRG v2,
  TSRG v1, SRG, and Mojang (Proguard) formats.
* `JarParser` walks every `.class` entry of a JAR and produces a
  `ClassInfo` tree including method-level instruction fingerprints.
* `SimilarityMatcher` returns 100% self-match (matchedMethods=9/9,
  matchedFields=2/2 on `org.objectweb.asm.AnnotationVisitor`).
* `MappingApplier` (ASM `Remapper` + `ClassRemapper`) remaps every class in
  the source JAR; signature files (META-INF/*.SF/RSA/DSA/EC) are stripped
  automatically because remapping invalidates them.
* `TinyWriter` round-trips the mapping into a well-formed Tiny v2 file.
* CFR (0.152) decompiles the remapped ASM jar to 36 `.java` files in ~3 s.
* Vineflower (1.10.1) decompiles the same jar to 36 `.java` files in ~6 s.

## License

GPL-3.0-only — see [`LICENSE`](./LICENSE).
