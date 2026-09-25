# Drop decompiler jars here.

This directory is the default drop location for the optional JetBrains
Fernflower jar (`fernflower-*.jar`). The `DecompilerLocator` looks for
jars matching the substring "fernflower" on the runtime classpath —
including any `*.jar` you place in this directory if you uncomment the
`fileTree(dir: 'lib', include: ['fernflower*.jar'])` line in
`build.gradle`.

The `lib/` directory itself is committed (with this placeholder file)
so the build configuration works out-of-the-box.
