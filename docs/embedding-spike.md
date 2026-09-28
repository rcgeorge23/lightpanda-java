# Issue #1: embedding spike findings

**Checked:** 2026-09-28

**Upstream API:** [lightpanda-io/browser#3096](https://github.com/lightpanda-io/browser/pull/3096)

## Upstream status

PR #3096 is still open and marked draft. Its proposed `include/lightpanda.h`
and `src/c_api.zig` provide a C ABI over Lightpanda's browser-tool surface.
Built its exact head (`bbcc2f795d23d891dbbcd84f48773ea9be07fb63`) as a Linux
x86_64 shared library with Zig 0.16.0 and ran the Java integration test against
that library.

The ABI is sufficient in shape for the first Java interaction flow:

- `lp_init` / `lp_shutdown` manage the process-wide browser runtime.
- `lp_session_new` / `lp_session_close` create isolated page, cookie, and JS
  state.
- `lp_call` dispatches existing browser tools, including `goto`, `fill`,
  `click`, `waitForSelector`, `extract`, and `evaluate`.
- `lp_last_error` and `lp_browser_last_error` provide diagnostic names;
  `lp_tools_json` describes tool schemas.

The API does not expose separate Java-native DOM locator objects. This spike
passes tool names and JSON argument objects through `lp_call`; a higher-level
Java API can be designed after the native boundary has been exercised.

## ABI implications for Java

- **FFM is viable without JNI:** the ABI uses ordinary C functions and opaque
  pointers, which Java's stable FFM API (JDK 22+) can call with downcall
  method handles.
- **Single terminal initialization:** `lp_init` may be called once per
  process; `lp_shutdown` is terminal because V8 cannot be reinitialized.
  Separate tests that initialize the library need separate JVM processes.
- **Thread affinity:** every browser/session call must run on the thread that
  initialized Lightpanda. The spike uses a confined arena and checks the
  creating thread before native calls. This rules out parallel calls across
  sessions in the current ABI.
- **Borrowed native result memory:** `lp_result.text` is a pointer plus byte
  length and is invalidated by the next call on that session. The wrapper
  copies UTF-8 bytes into a Java `String` before returning.
- **Platform scope:** this prototype maps `size_t` as a 64-bit value and the
  C result struct layout for 64-bit platforms. Linux x86_64 is the initial
  target; other data models need validation before being advertised.
- **Lifecycle:** Java `AutoCloseable` scopes the browser and sessions. Closing
  a browser closes remaining sessions before calling `lp_shutdown`.

## Prototype and test

`src/main/java/io/lightpanda/spike/EmbeddedLightpanda.java` binds the lifecycle
functions and `lp_call` directly with FFM. The opt-in integration test starts
a local HTTP server, navigates to a form, fills it, clicks submit, waits for
JavaScript to create a welcome element, and extracts its text.

The default Debug build has `DF_STATIC_TLS` and an `R_X86_64_TPOFF64`
relocation. FFM late loading fails with `Cannot open library`; the dynamic
loader reports `cannot allocate memory in static TLS block`. Building with
`zig build lib -Ddev_fast=false -Doptimize=ReleaseFast` removes the static-TLS
flag and allows late lookup.

The local form interaction passed against this ReleaseFast library on JDK 25
from a normally started JVM, with `LD_PRELOAD` unset. The FFM library lookup is
associated with `Arena.global()` so the shared object remains mapped through
JVM shutdown. An earlier run using a confined library arena passed the test
body but crashed during JVM shutdown after unloading Lightpanda; retaining the
mapping avoided that crash. Closing the Java browser still closes sessions
and calls `lp_shutdown`, but does not unload the library.

The real-library test is verified on Linux x86_64 and JDK 25. JDK 22 and
non-Linux data models remain unverified. The upstream C ABI is still draft, and
its default Debug build is not suitable for late loading; consumers must use
the documented ReleaseFast build or another build verified not to require
static TLS.

## Open questions after the spike

1. Agree with upstream on the supported release build configuration and ensure
   a published native artifact uses a late-loadable TLS model.
2. Confirm upstream support and intended behavior for submission events,
   navigation waits, timers/event-loop pumping, and repeated sessions.
3. Measure startup, navigation, and interaction costs against standalone
   Lightpanda over CDP; the current API's single-thread contract may limit
   concurrent test throughput.
4. Decide whether direct tool/JSON calls are acceptable beneath a small Java
   `Page`/locator API, or whether upstream should expose more purpose-built C
   functions.
5. Validate lifecycle behavior in forked JUnit processes and add platform
   packaging only after the native ABI stabilizes.

## Conclusion

The proposed C surface can execute the MVP interaction flow through FFM against
a real native build loaded after JVM startup, provided the Linux library is
built in ReleaseFast mode and remains mapped for the JVM lifetime. The default
Debug build still requires startup preloading, and the C ABI itself is still
draft.
