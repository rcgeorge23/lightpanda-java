# Lightpanda Java embedding spike

An experimental Java 22+ Foreign Function & Memory (FFM) binding for the
proposed Lightpanda C embedding API. This repository currently targets the
upstream draft at [lightpanda-io/browser#3096](https://github.com/lightpanda-io/browser/pull/3096);
it is a technical spike, not a released Java API.

The spike includes an FFM wrapper for browser/session lifecycle, tool calls,
and `lp_session_pump` for advancing timer/background work between calls. An
opt-in JUnit test serves a local form and exercises navigation, typing,
clicking, pump-driven timer progress, and extracting text.

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

Build the upstream library from the repository at the PR head with Zig 0.16.0
and its documented prerequisites. Use `ReleaseFast`: the default Debug build
contains static TLS and cannot be loaded into an already-running JVM on Linux.

```sh
zig build lib -Ddev_fast=false -Doptimize=ReleaseFast
```

Then run the integration test:

```sh
./gradlew test -PlightpandaLibrary=/absolute/path/to/liblightpanda.so
```

The native test is skipped when `lightpandaLibrary` is not supplied. FFM is
stable in JDK 22+; native access is enabled for the test JVM by the Gradle
configuration. Applications using this binding should enable native access
for the module containing it (for example, `--enable-native-access=ALL-UNNAMED`
when using it from the class path). The wrapper keeps the native library mapped
for the JVM lifetime: unloading it after `lp_shutdown` caused a JVM teardown
crash in testing. Closing the Java browser shuts down Lightpanda but does not
unload the shared library.
