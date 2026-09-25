# Contributing to JarRemapper

Thanks for your interest in improving JarRemapper! This document is the
short-form guide for getting set up and submitting changes.

## Development environment

* JDK 21 (Temurin or any reference JDK).
* Gradle 8.5+ (or just use the bundled `./gradlew` wrapper).
* An IDE with JavaFX support (IntelliJ IDEA Community Edition works).

## Build & test

```bash
./gradlew build           # compiles main + test, runs JUnit 5 tests
./gradlew test            # just run tests
./gradlew guiRun         # launch the JavaFX GUI
./gradlew distZip        # produce a distribution zip in build/distributions/
```

## Project layout

```
src/main/java/io/github/jarremapper/      application source
src/main/resources/                       FXML, CSS, logback.xml
src/test/java/                            JUnit 5 unit tests
build.gradle                              build config (Groovy DSL)
.github/workflows/build.yml               CI (JDK 21 + Gradle build)
lib/                                      drop-in location for the
                                          optional JetBrains Fernflower jar
```

## Submitting changes

1. Fork the repo, create a feature branch (`feat/my-thing`).
2. Make your change. Add or update unit tests under `src/test/java/`.
3. Run `./gradlew build` — make sure all tests pass.
4. Push and open a pull request against `main`.
5. CI will re-run the build on GitHub Actions; green CI is required
   for merge.

## Coding conventions

* Use the existing Java 21 style (records, switch expressions, `var`
  for locals where the type is obvious).
* 4-space indent, no tabs.
* Keep public classes documented with Javadoc — at minimum a one-line
  class-level comment explaining the role.
* Don't commit binary artifacts under `build/` or `dist/` (the
  `.gitignore` excludes them).

## Roadmap (good first issues)

* Better persistence: save the partial mapping to `output/mappings.tiny.partial`
  every 50 reviewed classes so the user can resume after a crash.
* Heuristic name proposal: auto-suggest names from `<clinit>` LDC string
  constants, like Enigma's `BuiltinNameProposalPlugin`.
* Table cell editing: make `colTarget` editable so users can rename a
  class after the Save dialog has closed.
* Output directory UI: let the user change the output directory from
  the toolbar instead of the hard-coded `./output`.
