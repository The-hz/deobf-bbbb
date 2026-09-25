# Prebuilt Distribution of JarRemapper

This directory contains everything needed to run JarRemapper on a machine with JDK 21
installed, without rebuilding from source.

## Files

```
dist/
├── run.sh                     # Linux/macOS launcher
├── run.bat                    # Windows launcher
├── lib/                       # All runtime dependencies (JavaFX, ASM, CFR, Vineflower, Quiltflower, SLF4J, Logback)
│   ├── javafx-base-21.0.2-linux.jar
│   ├── javafx-controls-21.0.2-linux.jar
│   ├── javafx-fxml-21.0.2-linux.jar
│   ├── javafx-graphics-21.0.2-linux.jar
│   ├── asm-9.7.jar  asm-tree-9.7.jar  asm-commons-9.7.jar
│   ├── cfr-0.152.jar
│   ├── vineflower-1.10.1.jar
│   ├── quiltflower-1.9.0.jar
│   ├── slf4j-api-2.0.13.jar
│   ├── logback-classic-1.5.6.jar
│   └── logback-core-1.5.6.jar
├── app/                       # Compiled application classes
│   └── io/github/jarremapper/...
└── resources/                 # FXML, CSS, logback.xml
    └── io/github/jarremapper/ui/...
```

## Usage (Linux/macOS)

```bash
cd dist
chmod +x run.sh
./run.sh
```

## Usage (Windows)

```bat
cd dist
run.bat
```

## Notes

* On Linux/macOS, the JavaFX Linux jars are bundled. On Windows you must
  download `javafx-base-21.0.2-win.jar` etc. from
  https://gluonhq.com/products/javafx/ and replace the Linux jars in
  `lib/`. The launcher script matches `javafx-*-win.jar` automatically.
* The `output/` directory is created in the current working directory when
  the pipeline runs.
