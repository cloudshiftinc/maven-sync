# Maven Sync — Agent Notes

## Pre-commit

Run `./gradlew precommit` before every commit. It:

1. Runs `ktfmtFormat` first, rewriting any non-conforming Kotlin source.
2. Then runs `check` (compile, tests, and — in CI — `ktfmtCheck`).

Ordering is enforced via `mustRunAfter` so formatting always lands before
verification. CI sets `CI=true`, which disables `ktfmtFormat` and enables
`ktfmtCheck`, so unformatted code committed locally will fail CI. See
`build.gradle.kts` for the task wiring.
