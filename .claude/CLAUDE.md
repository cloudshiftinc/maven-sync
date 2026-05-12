# maven-sync

A Kotlin/JVM CLI that incrementally mirrors artifacts from a source Maven repository to a target Maven repository (commonly AWS CodeArtifact). Intended to run as a one-time migration or on a schedule. Only versions missing from the target are transferred. SNAPSHOT versions are excluded.

Published to Maven Central as a `distZip` (group `io.cloudshiftdev.maven-sync`); consumers download the zip and run `bin/maven-sync <config.json>`.

## High-level architecture

```
MavenSyncMain
  └── loadConfiguration  (Hoplite: JSON files + CLI args + defaults.json)
  └── executeSync
        ├── source: MavenHttpRepository
        ├── target: MavenHttpRepository
        └── coroutine pipeline:
              producer  → crawlArtifactMetadata  → Channel<ArtifactMetadata>
              N workers → handleArtifact         (N = artifactConcurrency)
                            ├── target.queryArtifactMetadata
                            ├── diff source vs target versions
                            ├── source.listArtifactVersionAssets
                            ├── source.copyAsset → target.uploadAsset  (per asset)
                            └── target.releaseVersion  (rewrites maven-metadata.xml)
```

Crawling strategy (`crawlDirectoryListings`):
1. Parse the HTML directory listing at a URL.
2. If `maven-metadata.xml` is present with versions, emit metadata and stop.
3. Otherwise, if every subdirectory parses as an artifact version, synthesize metadata from the directory names.
4. Otherwise, recurse into subdirectories with `crawlDelay` between requests.

The target repository's `maven-metadata.xml` is rewritten (`releaseVersion`) after each version is uploaded so subsequent runs see it.

## Layout

```
src/main/kotlin/io/cloudshiftdev/mavensync/
  MavenSyncMain.kt          entry point, config loading, sync orchestration (producer/consumer)
  SyncConfig.kt             config data classes (SyncConfig, Source/TargetRepositoryConfig, credentials)
  MavenHttpRepository.kt    repo abstraction: crawl, query metadata, list/copy/upload assets, release version, maven-metadata.xml writer
  MavenHttpClient.kt        Ktor (OkHttp) client wrapper: download, upload, parse content, parse directory listings
  DirectoryListingParser.kt parses HTML `<pre>`-style Apache/Nexus/etc. directory listings via Ksoup
  Types.kt                  value classes (Group, Artifact, ArtifactVersion, Filename, Coordinates), MavenSpec constants
  Extensions.kt             small shared helpers

src/main/resources/
  config/defaults.json      defaults merged last by Hoplite
  logback.xml               logging config

build.gradle.kts            Kotlin JVM application + Maven Central publishing of distZip; ktfmt; explicit API
settings.gradle.kts         requires JDK 21; foojay toolchains; Develocity
gradle/libs.versions.toml   version catalog
test.json                   sample configuration
```

## Key dependencies

- **Ktor client (OkHttp engine)** — HTTP, with auth + logging plugins
- **Ksoup** — HTML parsing for directory listings
- **Hoplite** (+ JSON) — layered config loading (CLI args, file sources, resource defaults)
- **kotlinx.coroutines** — producer/consumer channel pipeline
- **Apache `maven-artifact`** — `DefaultArtifactVersion` for version parsing
- **Logback + kotlin-logging (oshai)** — logging; `--debug` raises `io.cloudshiftdev.mavensync` to DEBUG
- **Kotest (JUnit5)** — test framework (no tests checked in yet)

## Build / run

- `./gradlew build` — compile, format check (CI only), test
- `./gradlew precommit` — runs `check` and `ktfmtFormat`
- `./gradlew run --args="test.json"` — run locally against a config
- `./gradlew installDist` / `distZip` — produce runnable distribution under `build/install/maven-sync/` or `build/distributions/`
- Requires JDK 21 (enforced in `settings.gradle.kts`)
- Kotlin `explicitApi()` is on — public declarations need explicit visibility
- ktfmt with `kotlinLangStyle`: formats on local builds, checks on CI (`CI` env var)

## Conventions

- All domain identifiers are `@JvmInline value class`es (`Group`, `Artifact`, `ArtifactVersion`, `Filename`) — don't pass raw strings.
- The whole app is internal except `main`; the published artifact is the runnable zip, not a library.
- Be considerate of source repos: `crawlDelay` between directory listings, `downloadDelay` between versions, `artifactConcurrency` caps parallel asset transfers.
