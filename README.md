# Lightpanda Java

**Embed the [Lightpanda](https://github.com/lightpanda-io/browser) headless browser directly in a Java process.**

Lightpanda Java is an **experimental Java 22+ binding** for Lightpanda's proposed C embedding API. It uses the Java Foreign Function & Memory (FFM) API: **no JNI code, Chrome installation, or separate Chrome DevTools Protocol (CDP) server** is required for this embedding approach.

Create a session, navigate, fill forms, click elements, evaluate JavaScript, extract page content, and capture console diagnostics. The API is intentionally low-level: operations are named native tools taking JSON, not Selenium/Playwright-style `Page` or `Locator` objects.

> **Experimental native ABI:** This library targets the proposed API in [lightpanda-io/browser#3096](https://github.com/lightpanda-io/browser/pull/3096), which is not a stable released embedding interface. You must build or supply a **compatible native shared library**. The verified configuration is **Linux x86_64 + JDK 25 + Zig 0.16.0** at a pinned upstream commit; JDK 22 is the minimum Java API version. See [compatibility and limitations](#compatibility-and-limitations) before using it in production.

## Quick start

### 1. Add the Java dependency

Requirements:

- JDK **22 or later** with native access enabled at runtime, on a **64-bit JVM**.
- The published `io.github.rcgeorge23:lightpanda-java` artifact, or a local build of this repository.
- A compatible `liblightpanda.so` (the native binary is **not bundled** with the Java JAR).

Choose an **actually published** version from [Maven Central](https://central.sonatype.com/namespace/io.github.rcgeorge23). Versions built by the repository's main-branch workflow follow the pattern `0.1.0-main.<run-number>.<run-attempt>`; the pattern is **not** a literal dependency version.

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.rcgeorge23:lightpanda-java:REPLACE_WITH_PUBLISHED_VERSION")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
```

**Gradle (Groovy DSL)**

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.rcgeorge23:lightpanda-java:REPLACE_WITH_PUBLISHED_VERSION'
}

tasks.withType(JavaExec).configureEach {
    jvmArgs '--enable-native-access=ALL-UNNAMED'
}
tasks.test {
    jvmArgs '--enable-native-access=ALL-UNNAMED'
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.rcgeorge23</groupId>
    <artifactId>lightpanda-java</artifactId>
    <version>REPLACE_WITH_PUBLISHED_VERSION</version>
</dependency>
```

Pass `--enable-native-access=ALL-UNNAMED` to the **JVM executing your application**, not only to the compiler. For example:

```sh
java --enable-native-access=ALL-UNNAMED -cp "your-classpath" com.example.BrowseExample
```

The Java package is currently named `io.lightpanda.spike`; use the actual public API names shown below.

### 2. Build the compatible native library

This is the important extra step: a normal Lightpanda executable does **not** provide the embedding `lp_*` symbols.

The verified native build uses a pinned upstream revision of the **proposed** embedding ABI. On Linux x86_64, with **Zig 0.16.0** and the upstream build prerequisites (including Rust) installed:

```sh
git clone https://github.com/lightpanda-io/browser.git
cd browser
git checkout bbcc2f795d23d891dbbcd84f48773ea9be07fb63
zig build lib -Ddev_fast=false -Doptimize=ReleaseFast
```

Locate the resulting `liblightpanda.so` (normally under `zig-out/lib/`) and provide its **absolute path**:

```sh
export LIGHTPANDA_LIBRARY=/absolute/path/to/liblightpanda.so
```

**Use `ReleaseFast`:** a Debug build was observed to require static TLS and fail when loaded by an already-running JVM on Linux. Different operating systems, native revisions, and build configurations have not been verified; check the [upstream PR](https://github.com/lightpanda-io/browser/pull/3096) and [embedding findings](docs/embedding-spike.md) before deviating from the pin.

### 3. Navigate and evaluate JavaScript

This is a complete Java class. With the native library environment variable set, it opens a real page, evaluates its title, and closes both the session and browser:

```java
package com.example;

import io.lightpanda.spike.EmbeddedLightpanda;
import java.nio.file.Path;

public class BrowseExample {
    public static void main(String[] args) {
        String libraryPath = System.getenv("LIGHTPANDA_LIBRARY");
        if (libraryPath == null || libraryPath.isBlank()) {
            throw new IllegalStateException("Set LIGHTPANDA_LIBRARY to liblightpanda.so");
        }

        try (EmbeddedLightpanda browser =
                     EmbeddedLightpanda.load(Path.of(libraryPath));
             EmbeddedLightpanda.Session page = browser.newSession()) {

            page.call("goto", """
                    {"url":"https://example.com"}
                    """);

            String result = page.call("evaluate", """
                    {"script":"document.title"}
                    """);

            // Native tool result: inspect its JSON envelope, not just a raw title.
            System.out.println(result);
        }
    }
}
```

The `call(toolName, jsonArguments)` method returns the **native tool response as a String**. It does not parse JSON or return a Java DOM object; use your preferred JSON library to interpret the response.

**Lifecycle matters:** a browser may have several isolated sessions, but native calls must remain on its creating thread, and closing the browser is terminal for that JVM (see [restrictions](#compatibility-and-limitations)). Do not create and close a fresh native browser for every test in one JVM.

## Browser automation recipes

The following examples run **inside an existing `EmbeddedLightpanda.Session page`**, created as in the quick start.

### Fill and submit a form

Suppose your application is running at `http://127.0.0.1:8080/` with this markup and script:

```html
<form id="signup">
  <input id="email" type="email">
  <button type="submit">Sign up</button>
</form>
<script>
  document.querySelector("#signup").addEventListener("submit", event => {
    event.preventDefault();
    const message = document.createElement("p");
    message.id = "welcome";
    message.textContent = "Welcome " + document.querySelector("#email").value;
    document.body.append(message);
  });
</script>
```

Your Java code can drive that interaction:

```java
page.call("goto", """
        {"url":"http://127.0.0.1:8080/"}
        """);

page.call("fill", """
        {"selector":"#email","value":"fred@example.com"}
        """);

page.call("click", """
        {"selector":"button[type=submit]"}
        """);

page.call("waitForSelector", """
        {"selector":"#welcome"}
        """);

// The schema parameter is itself a JSON-encoded string.
String extracted = page.call("extract",
        "{\"schema\":\"{\\\"welcome\\\":\\\"#welcome\\\"}\"}");
System.out.println(extracted);
```

The exact calls above are exercised against a self-hosted HTML fixture by [`EmbeddedLightpandaIntegrationTest`](src/test/java/io/lightpanda/spike/EmbeddedLightpandaIntegrationTest.java). The test checks that the extracted content includes `Welcome fred@example.com`.

For simpler values you can evaluate JavaScript directly:

```java
String welcome = page.call("evaluate", """
        {"script":"document.querySelector('#welcome')?.textContent"}
        """);
System.out.println(welcome); // Native tool response containing the evaluated result
```

### Advance timers and asynchronous work

JavaScript timers do not necessarily progress without explicit pumping. `Session.pump()` advances background work on the **calling (owner) thread** and returns a recommended sleep delay in milliseconds; it does not sleep for you.

```java
import java.util.concurrent.TimeUnit;

// After navigating to a page:
page.call("evaluate", """
        {"script":"setTimeout(() => document.body.dataset.ready = 'yes', 20)"}
        """);

long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
boolean ready = false;

while (System.nanoTime() < deadline) {
    long suggestedDelayMs = page.pump();
    Thread.sleep(Math.max(1L, Math.min(suggestedDelayMs, 10L)));

    String state = page.call("evaluate", """
            {"script":"document.body.dataset.ready === 'yes'"}
            """);
    if (state.contains("true")) {
        ready = true;
        break;
    }
}
if (!ready) {
    throw new IllegalStateException("Timed out waiting for the timer");
}
```

For exact status checking, parse the native `evaluate` response rather than using `String.contains`. The shortened check above matches the repository's current native integration test.

### Capture console errors and unhandled JavaScript failures

```java
import io.lightpanda.spike.EmbeddedLightpanda.ConsoleMessage;

page.call("goto", """
        {"url":"http://127.0.0.1:8080/"}
        """);

// Listeners are document-scoped: install again after each navigation.
page.installJavascriptErrorCapture();

// Exercise the page. Pump if the page schedules async work.
page.call("click", """
        {"selector":"#save"}
        """);

for (ConsoleMessage message : page.drainConsoleMessages()) {
    if (message.isErrorLevel()) {
        System.err.println(message.origin() + ": " + message.text());
    }
}
```

`drainConsoleMessages()` returns and **clears** the current session's buffered messages. Direct `console.error` messages have origin `CONSOLE`. The injected listeners forward uncaught errors and unhandled rejections with `UNCAUGHT_EXCEPTION` and `UNHANDLED_REJECTION` origins.

The listener cannot see page-load errors that occurred before installation or errors from iframes/workers. The native tool currently does not expose source URLs, timestamps, or line/column numbers for these messages; native error hooks are being discussed in [lightpanda-io/browser#4722](https://github.com/lightpanda-io/browser/issues/4722).

### Handle tool failures

```java
import io.lightpanda.spike.LightpandaException;

try {
    page.call("click", """
            {"selector":"#missing"}
            """);
} catch (LightpandaException error) {
    System.err.println("Tool: " + error.tool());
    System.err.println("Native status: " + error.nativeStatusName().orElse("not supplied"));
    System.err.println("Native error: " + error.nativeError().orElse("not supplied"));
    System.err.println("Tool result: " + error.toolResult().orElse("not supplied"));
}
```

`LightpandaException` extends `IllegalStateException`. A failed native call may supply a status code/name and native error; an in-band tool error may instead supply only the tool-result payload. Do not assume every field is available.

## API reference

| API | Behaviour |
| --- | --- |
| `EmbeddedLightpanda.load(Path)` | Map a compatible native library and initialise Lightpanda |
| `browser.newSession()` | Create an isolated session |
| `session.call("goto", json)` | Navigate |
| `session.call("fill", json)` | Fill an element selected by CSS |
| `session.call("click", json)` | Activate a selected element |
| `session.call("waitForSelector", json)` | Wait for a matching element |
| `session.call("evaluate", json)` | Evaluate page JavaScript |
| `session.call("extract", json)` | Extract content using a selector schema |
| `session.pump()` | Advance timers/background work, returning a suggested delay |
| `session.drainConsoleMessages()` | Read and clear the session's console buffer |
| `session.installJavascriptErrorCapture()` | Install document-level error/rejection listeners |
| `session.close()`, `browser.close()` | Release native resources |

The accepted JSON argument shapes come from the corresponding Lightpanda native tool interfaces. There is no Java type-safe DSL around them yet.

## Compatibility and limitations

| Area | Current restriction |
| --- | --- |
| **Native ABI** | Experimental C embedding interface from draft upstream PR #3096. Versions must match. |
| **Platform** | 64-bit data model; verified on Linux x86_64/JDK 25. Java API compiles for JDK 22+. |
| **Threading** | **Single owner thread** for all browser and session operations, including `pump()` and `close()`. |
| **JVM lifetime** | The native runtime can be initialised only once per process. After `browser.close()` calls shutdown it **cannot be reinitialised** in that JVM. |
| **Library loading** | Shared library is not packaged; it stays mapped for the lifetime of the JVM to avoid an observed unload crash. |
| **Browser fidelity** | Lightpanda's web-platform and layout support is incomplete; this is not a drop-in replacement for Chrome/Playwright. |
| **Errors** | JavaScript listener capture starts after navigation and omits iframe/worker and earlier load-time errors. |
| **Licence** | This repository is **AGPL-3.0-only**; review the obligations for your use and distribution. |

If a test suite requires independent initialisation cycles, **fork a new JVM process for each cycle**. Reuse sessions inside one browser for workflows that can share a process and owner thread.

For ABI structure, memory ownership, native symbol expectations, and verified revisions, consult [`docs/embedding-spike.md`](docs/embedding-spike.md).

## Build and test from source

```sh
git clone https://github.com/rcgeorge23/lightpanda-java.git
cd lightpanda-java
./gradlew build
```

The regular build works without a native library; the native integration test is **skipped** unless a library path is provided. To run that integration test on the supported native configuration:

```sh
./gradlew test -PlightpandaLibrary=/absolute/path/to/liblightpanda.so
```

Alternatively set `LIGHTPANDA_LIBRARY` or the `lightpanda.library` system property. The project's Gradle test task already enables native access. The integration test starts a local HTTP server and verifies navigation, interaction, JavaScript timers, extraction, session isolation, diagnostics, and error reporting.

Main-branch builds publish signed prerelease artifacts through the [publishing workflow](.github/workflows/publish.yml) when its checks and publishing configuration succeed. See [GitHub issues](https://github.com/rcgeorge23/lightpanda-java/issues) for discussion and contributions.

## Licence

[GNU Affero General Public License v3.0 only](LICENSE). The upstream Lightpanda browser is also AGPL-licensed.
