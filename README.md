# Lightpanda Java embedding spike

An experimental Java 22+ Foreign Function & Memory (FFM) binding for the
proposed Lightpanda C embedding API. This repository currently targets the
upstream draft at [lightpanda-io/browser#3096](https://github.com/lightpanda-io/browser/pull/3096);
it is a technical spike, not a released Java API.

The spike includes an FFM wrapper for browser/session lifecycle and tool calls,
plus an opt-in JUnit test that serves a local form and exercises navigation,
typing, clicking, waiting for JavaScript-created DOM, and extracting text.

See [the spike findings](docs/embedding-spike.md) for ABI constraints, current
upstream status, and the checks that still require a built native library.

## Maven Central publishing

Pull requests targeting `main` are verified on JDK 22 and 25. Every push to
`main` publishes a signed release to Maven Central after both test runs pass.
Each publication gets a unique prerelease version in the form
`0.1.0-main.<run-number>.<run-attempt>`; update `baseVersion` in
`gradle.properties` to change the base version. The build uses
the checked-in Gradle 9.2.1 Wrapper; run `./gradlew` for all build tasks.

The artifact coordinates are `io.github.rcgeorge23:lightpanda-java`. The
`io.github.rcgeorge23` namespace must be registered and verified in Sonatype
Central before the first publication. GitHub Actions needs the
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `MAVEN_SIGNING_KEY`, and
`MAVEN_SIGNING_PASSWORD` repository secrets. The signing key secret must
contain an ASCII-armored private GPG key; the Central username and password
must be a Central Portal user-token pair. Artifact signatures are generated
from the in-memory key by the Gradle publishing plugin.

## Run the integration test

Requirements:

- JDK 22 or newer
- The Gradle Wrapper (downloads the pinned Gradle distribution on first run)
- A `liblightpanda` built from the upstream embedding PR

Build the upstream library using the instructions in its PR, then run:

```sh
./gradlew test -PlightpandaLibrary=/absolute/path/to/liblightpanda.so
```

The native test is skipped when `lightpandaLibrary` is not supplied. FFM is
stable in JDK 22+; native access is enabled for the test JVM by the Gradle
configuration. Applications using this binding should enable native access
for the module containing it (for example, `--enable-native-access=ALL-UNNAMED`
when using it from the class path).
