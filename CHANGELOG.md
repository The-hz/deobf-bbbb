# Changelog

All notable changes to JarRemapper are documented here.
This project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] — 2026-09-25

### Added
- Initial release of JarRemapper: a JavaFX desktop tool that transfers
  obfuscation mappings between JAR files via bytecode-similarity matching
  + embedded CFR / Fernflower / Vineflower decompilers.
- Six mapping parsers with format auto-detection: Tiny v1/v2, TSRG v1/v2,
  SRG, Mojang (Proguard).
- Similarity scoring with a weighted blend of:
    * Fine method fingerprint (rolling hash over opcode + descriptor
      operand — sensitive to instruction changes)
    * Coarse method fingerprint (opcode-category histogram — robust
      to obfuscator-added dead code like `iconst_0; pop`)
    * Method descriptor multiset (Jaccard-style Dice)
    * Field descriptor multiset
    * Class arity ratio (within 2×)
    * Access-flag bitwise overlap
    * Super-class / interface overlap (JDK refs only)
- Class-level bucket pre-filter that skips grossly-different pairs
  (constant factor 5–10× speedup on large JARs).
- ASM `Remapper`-based mapping applier that rewrites class / method / field
  names and automatically strips invalid META-INF signature files.
- Tiny v2 writer with tab/newline escaping and identity-entry support.
- JavaFX GUI: three drop zones (target JAR + mapping + unmapped JAR),
  threshold slider, decompiler combo, results table, log area, and a
  per-class review dialog with "Save / Skip / Skip All Remaining" buttons.
- 27 JUnit 5 unit tests covering all parsers, the matcher, the writer,
  and the bytecode hash functions.
- CI workflow (`.github/workflows/build.yml`) — JDK 21 + Gradle build
  runs on every push.
- `CONTRIBUTING.md`, `NOTICE` (third-party license attributions for the
  prebuilt distribution).

### Fixed
- **P0-1**: Field matching no longer collapses multiple same-type fields
  to a single orig name ("first wins" bug) — disambiguates via
  descriptor + access + initFp + declaration order.
- **P0-2**: Method fingerprint now has a coarse opcode-histogram fallback
  signal so obfuscator-inserted dead code doesn't zero out the score.
- **P0-3**: Class-level arity pre-filter wired into `matchAll` — reduces
  the brute-force O(n×m) loop's constant factor.
- **P0-4**: Added JUnit 5 test suite — was zero tests.
- **P0-5**: `MappingModel.mapField` ambiguous fallback now bails out
  (returns the obf name) instead of mispicking, preventing subclass
  field-shadowing misfires.
- **P1-7**: `askForName` uses a 5-minute timeout instead of unbounded
  `await()` — pipeline auto-Skips instead of hanging the worker thread.
- **P1-8**: `ProcessDecompiler.ensureEmptyOrClean` now refuses to
  recursively delete directories outside CWD / system temp.
- **P1-9**: Added "Skip All Remaining" button to the review dialog.
- **P1-10**: Stage 3 (matching) now reports per-class progress instead
  of staying at 25% until all classes are processed.
- **P1-12**: Decompiler child-JVM timeout is configurable via
  `AppConfig.decompilerTimeoutMinutes` (default 15 min).
- **P2-15**: Added `.github/workflows/build.yml` CI.
- **P2-18**: Created `lib/` directory with a README so the
  `fernflower*.jar` `fileTree(...)` line in `build.gradle` is no longer
  misleading.
- **P2-19**: `DecompilerLocator` now queries both `java.class.path`
  AND `ClassLoader.getURLs()` AND `./lib/` — works in IDE launches where
  the classpath is class-output directories rather than jars.
