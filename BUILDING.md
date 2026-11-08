# Building Fiximizer

## Windows one-click build

Run `build.bat` from the repository root.

The automatic Windows builder is adapted from the working builder in [MAKAKAIglitch/Rusify](https://github.com/MAKAKAIglitch/Rusify):

- Searches `JAVA_HOME`, `PATH`, common Java installation directories and Prism Launcher runtimes for a full JDK 21.
- Verifies both `java.exe` and `javac.exe`; only Java 21 is accepted.
- If JDK 21 is unavailable, downloads Eclipse Temurin 21 through the official Adoptium API.
- Verifies the archive against the SHA-256 checksum returned by Adoptium before extraction.
- Stores the isolated JDK in `.tools/jdk-21-temurin`.
- Does not modify system Java settings or replace another installed Java version.
- Uses the repository's pinned Gradle Wrapper and reuses Gradle caches.
- Builds the mod into `build/libs/`.

Examples:

```bat
build.bat
build.bat clean build
build.bat runClient
```

The first run may download JDK 21, Gradle and Minecraft/NeoForge dependencies. Later runs reuse cached files.

## Manual build

With an installed JDK 21:

```bat
gradlew.bat build
```

Linux/macOS:

```bash
./gradlew build
```

## OpenCL runtime

Fiximizer checks for OpenCL GPU devices through LWJGL. The OpenCL ICD/runtime is supplied by the installed GPU driver. The build script does not install a GPU driver or a separate OpenCL runtime.

## Output

The mod JAR is written to:

```text
build/libs/fiximizer-0.9.0-alpha.jar
```

## Local tools and caches

- Isolated JDK: `.tools/jdk-21-temurin`
- JDK download workspace: `.tools/downloads`
- Gradle wrapper, dependencies and build cache: standard Gradle user cache
- Compiled outputs: `build/`

All generated tools and build outputs are excluded from Git.
