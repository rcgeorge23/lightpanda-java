# Issue #1: embedding spike findings

**Checked:** 2026-09-28

**Upstream API:** [lightpanda-io/browser#3096](https://github.com/lightpanda-io/browser/pull/3096)

## Upstream status

PR #3096 is still open and marked draft. Its proposed `include/lightpanda.h`
and `src/c_api.zig` provide a C ABI over Lightpanda's browser-tool surface. The
PR describes a shared-library build, but no released or locally available
`liblightpanda` is present in this checkout.

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

This proves the intended Java-side call flow once run against the upstream
library. It has **not yet been run end-to-end**: this environment has JDK 25,
but no Zig toolchain or built `liblightpanda`. The Java source can still be
compiled locally against the JDK 22 API surface.

## Open questions after the spike

1. Build and run the integration test when upstream publishes/merges a usable
   shared-library build; verify struct layout and all calls against that exact
   binary.
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

The proposed C surface appears capable of the MVP interaction flow without
JNI or a browser process boundary. A minimal FFM wrapper is technically
straightforward, but the key success criteria remain unproven until the draft
native library can be built and the opt-in local-server test passes.
