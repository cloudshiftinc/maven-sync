# Overview

`maven-sync` synchronizes one Maven repository to another. It is designed to be run as either a one-time migration or a
cron job / scheduled task.

Artifacts are synchronized incrementally; versions that exist in the _source_ repository are only mirrored to the
_target_ repository if they do not already exist.

# Be Considerate

When synchronizing repositories, be considerate of the source repository. Crawling the repository too quickly can cause service impacts.

# Getting Started

`maven-sync` is distributed as a Java application, and can be run on any platform that supports Java 21 or later.

It can be acquired via any Maven-compatible client or direct download, as shown below:

```shell
MAVEN_SYNC_VERSION=0.5.1
curl -v --fail-with-body -o maven-sync.zip https://repo1.maven.org/maven2/io/cloudshiftdev/maven-sync/maven-sync/${MAVEN_SYNC_VERSION}/maven-sync-${MAVEN_SYNC_VERSION}.zip
unzip maven-sync.zip
cd maven-sync-${MAVEN_SYNC_VERSION}
bin/maven-sync <configuration files>
```

# Configuration

`maven-sync` is primarily configured via a JSON file, with command-line and environment variable overrides available.

A minimal configuration file, for repositories that do not require authentication, is shown below:

```json
{
  "source": {
    "url": "https://my.repo.com/maven"
  },
  "target": {
    "url": "https://<domain>-<account>.d.codeartifact.<region>.amazonaws.com/maven/<repository>/"
  }
}
```

The full configuration schema is shown below:

```json
{
  "transferChecksums": false,
  "transferSignatures": true,
  "artifactConcurrency": 3,
  "source": {
    "url": "string",
    "credentials": {
      "username": "string",
      "password": "string"
    },
    "logHttpHeaders": false,
    "crawlDelay": "100ms",
    "downloadDelay": "1s",
    "paths": [
      "path1",
      "path2"
    ]
  },
  "target": {
    "url": "string",
    "credentials": {
      "username": "string",
      "password": "string"
    },
    "logHttpHeaders": false
  }
}
```

| Configuration attribute | Description                                                                                                                                   |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `transferChecksums`     | If `true`, checksums (.md5, .sha1, .sha256, .sha512) are transferred from the source repository to the target repository. Default is `false`. |
| `transferSignatures`    | If `true`, signatures (.asc files) are transferred from the source repository to the target repository. Default is `true`.                    |
| `artifactConcurrency`   | The number of artifacts to transfer concurrently. Default is `3`.                                                                             | 
| `source.url`            | The URL of the source repository. Required.                                                                                                   |
| `source.credentials`    | The credentials to use when accessing the source repository. Optional.                                                                        |
| `source.logHttpHeaders` | If `true`, HTTP headers are logged when accessing the source repository. Default is `false`.                                                  |
| `source.crawlDelay`     | The delay between crawling index pages in the source repository. Default is `100ms`.                                                          |
| `source.downloadDelay`  | The delay between downloading artifact versions from the source repository. Default is `1s`.                                                  |
| `source.paths`          | The paths to synchronize from the source repository. Optional. When not provided the entire repository is synchronized.                       |
| `target.url`            | The URL of the target repository. Required.                                                                                                   |
| `target.credentials`    | The credentials to use when accessing the target repository. Optional.                                                                        |
| `target.logHttpHeaders` | If `true`, HTTP headers are logged when accessing the target repository. Default is `false`.                                                  |

When multiple configuration file fragments are provided, they are merged together. Configuration attributes are resolved in the order provided.

In addition to specifying configuration attributes in one or more JSON configuration files they can be provided via command-line arguments or environment variables:

* Command line attributes override configuration file attributes, e.g. `--source.url=https://...`
* Environment variables are resolved by using the `${{env:VAR_NAME}}` syntax in a configuration file, e.g. `"${{ env:CODEARTIFACT_AUTH_TOKEN }}"`

# Examples

Synchronizing from a source Maven repository to an AWS CodeArtifact repository:

```json
 {
  "source": {
    "url": "https://my.repo.com/maven",
    "paths": []
  },
  "target": {
    "url": "https://<domain>-<account>.d.codeartifact.<region>.amazonaws.com/maven/<repository>/",
    "credentials": {
      "username": "aws",
      "password": "${{ env:CODEARTIFACT_AUTH_TOKEN }}"
    }
  }
}

```
This can be combined with this AWS CLI command to obtain a CodeArtifact authentication token:

```shell
 export CODEARTIFACT_AUTH_TOKEN=`aws codeartifact get-authorization-token --domain <domain> --domain-owner <owner> --query authorizationToken --output text`
```

# Integration tests

`MavenSyncIntegrationTest` exercises everything `maven-sync` does against a source repository — crawl, parse directory listings and `maven-metadata.xml`, target-diff, and per-version asset listing — *up to but excluding* any upload to the target. Nothing is written to the target repo (the target is faked with an empty in-memory stand-in, so every source version appears "missing" and the per-version asset listing runs for all of them).

The test is gated on the `MAVEN_SYNC_IT_CONFIG` environment variable. When unset, the test is skipped and a regular `./gradlew test` does no network IO.

To run it:

```shell
export MAVEN_SYNC_IT_CONFIG=$PWD/local-it.json   # any path outside the repo, or under a gitignored dir
./gradlew test --tests 'io.cloudshiftdev.mavensync.MavenSyncIntegrationTest'
```

The config file uses the standard configuration schema layered over the defaults. The `target` block is required by the schema but its `url` is **not contacted** — any placeholder is fine:

```json
{
  "source": {
    "url": "https://my.repo.com/maven",
    "credentials": {
      "username": "${{ env:MY_REPO_USER }}",
      "password": "${{ env:MY_REPO_PASS }}"
    },
    "paths": ["com/example/some-group"]
  },
  "target": {
    "url": "https://unused.invalid/"
  }
}
```

When the test runs it writes a deterministic JSON report to `build/reports/maven-sync-it/discovery-report.json` listing every discovered `group:artifact` → `version` → asset filenames. The test asserts that at least one artifact was found, every artifact has at least one version, and every version has at least one asset.

To lock the result in as a regression snapshot, copy the report to a stable location and set `MAVEN_SYNC_IT_EXPECTED` to its path:

```shell
cp build/reports/maven-sync-it/discovery-report.json ./my-source.expected.json
export MAVEN_SYNC_IT_EXPECTED=$PWD/my-source.expected.json
./gradlew test --tests 'io.cloudshiftdev.mavensync.MavenSyncIntegrationTest'
```

