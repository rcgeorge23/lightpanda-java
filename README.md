# Lightpanda Java

Experimental Java 22+ bindings for using the [Lightpanda](https://github.com/lightpanda-io/browser) headless browser **in-process**, through Java's Foreign Function & Memory (FFM) API.

**You do not need Diglet to use this library.** A Java application can create a Lightpanda browser session directly, navigate to a web page, fill forms, click elements, evaluate JavaScript and extract page content. No Chrome installation or separately running CDP server is needed for this embedding path. You **do** need a compatible native `liblightpanda` shared library.

> **Experimental:** This is a small FFM binding to the proposed Lightpanda C embedding ABI in [lightpanda-io/browser#3096](https://github.com/lightpanda-io/browser/pull/3096), not a stable Playwright/Selenium-style Java API. The native ABI can change. See [spike findings](docs/embedding-spike.md) for the tested revision and limitations.

## What can I do with it?

The current Java API is intentionally small:

| Java API | Purpose |
| --- | --- |
| `EmbeddedLightpanda.load(Path)` | Load the native shared library and initialise the browser |
| `browser.newSession()` | Create an isolated browsing session |
| `session.call("goto", json)` | Navigate to a URL |
| `session.call("fill", json)` | Enter text into a CSS-selected element |
| `session.call("click", json)` | Click an element |
| `session.call("waitForSelector", json)` | Wait for an element to appear |
| `session.call("evaluate", json)` | Execute JavaScript in the page |
| `session.call("extract", json)` | Extract structured content using a selector schema |
| `session.pump()` | Advance timers and other background work |
| `session.close()` / `browser.close()` | Release session/browser resources |

The `call` method takes the **native tool name and a JSON argument string**, and returns the native tool's result as a Java `String`. There are no Java `Page` or locator objects yet; you supply CSS selectors and JSON yourself. Tool failures throw `IllegalStateException`.

This is useful for lightweight web extraction, automation of forms and Java integration tests. Diglet can orchestrate such work, but it is not a dependency of `lightpanda-java`.

## Add it to your Java project

You need **JDK 22 or newer** and a **64-bit JVM**. The demonstrated native configuration is Linux x86_64 with JDK 25; other platforms/JDKs have not yet been verified.

The Gradle/Maven artifact coordinates are `io.github.rcgeorge23:lightpanda-java`. Releases from the `main` workflow use versions such as `0.1.0-main.<run-number>.<run-attempt>`. Substitute an **actual published version** from [Maven Central](https://central.sonatype.com/namespace/io.github.rcgeorge23); the example version pattern below is not a literal downloadable version.

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    implementation("io.github.rcgeorge23:lightpanda-java:<published-version>")
}
```

**Maven**

```xml
<dependency>
    <groupId>io.github.rcgeorge23</groupId>
    <artifactId>lightpanda-java</artifactId>
    <version><!-- published version --></version>
</dependency>
```

The Java artifact does **not** include the native `liblightpanda.so`. See [Build the native library](#build-the-native-library) below.

Enable native access when launching your application:

```sh
java --enable-native-access=ALL-UNNAMED -cp "your-classpath" com.example.BrowseExample
```

For a Gradle application, configure the JVM that actually runs your code (and separately the test JVM, if applicable). For example:

```kotlin
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
```

## Example 1: Open a page and read its title

Set `LIGHTPANDA_LIBRARY` to the **absolute path** of your compatible native library:

```sh
export LIGHTPANDA_LIBRARY=/absolute/path/to/liblightpanda.so
```

```java
package com.example;

import io.lightpanda.spike.EmbeddedLightpanda;
import java.nio.file.Path;

public class BrowseExample {
    public static void main(String[] args) {
        Path nativeLibrary = Path.of(System.getenv("LIGHTPANDA_LIBRARY"));

        try (EmbeddedLightpanda browser = EmbeddedLightpanda.load(nativeLibrary);
             EmbeddedLightpanda.Session page = browser.newSession()) {

            page.call("goto", """
                    {"url":"https://example.com"}
                    """);

            String result = page.call("evaluate", """
                    {"script":"document.title"}
                    """);

            // Result is the native tool response (not necessarily a plain title).
            System.out.println(result);
        }
    }
}
```

This runs the embedded browser from your Java process, **without Diglet**.

## Example 2: Fill a form, submit and extract the result

Given a page containing an input with `id="email"`, a submit button and an element with `id="welcome"` that appears after submission, the following calls can be made inside a live `page` session:

```java
page.call("goto", """
        {"url":"http://localhost:8080/"}
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

// "schema" is itself a JSON-encoded string containing the extraction selectors.
String result = page.call("extract",
        "{\"schema\":\"{\\\"welcome\\\":\\\"#welcome\\\"}\"}");

System.out.println(result);
```

These selectors and the local URL are examples: adapt them to your site's markup. The repository's [native integration test](src/test/java/io/lightpanda/spike/EmbeddedLightpandaIntegrationTest.java) runs this flow against a local HTTP-served JavaScript form and asserts that the extracted text contains `Welcome fred@example.com`.

You can also query the DOM directly:

```java
String result = page.call("evaluate", """
        {"script":"document.querySelector('#welcome')?.textContent"}
        """);
System.out.println(result);
```

## Example 3: Advance JavaScript timers

Some workflows need Lightpanda's background work to advance between tool calls. `pump()` executes one slice of work **on the same Java thread** and returns the native library's recommended delay in milliseconds.

```java
import java.util.concurrent.TimeUnit;

// Inside a live page session, after navigation:
page.call("evaluate", """
        {"script":"setTimeout(() => document.body.dataset.ready = 'yes', 20);"}
        """);

long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
boolean ready = false;

while (System.nanoTime() < deadline) {
    long recommendedDelayMs = page.pump();
    Thread.sleep(Math.max(1L, Math.min(recommendedDelayMs, 10L)));

    String state = page.call("evaluate", """
            {"script":"document.body.dataset.ready === 'yes'"}
            """);
    if (state.contains("true")) {
        ready = true;
        break;
    }
}
if (!ready) {
    throw new IllegalStateException("Timed out waiting for the JavaScript timer");
}
```

The delay is a recommendation, not an automatic sleep: your Java code controls when to pump again. The [integration test](src/test/java/io/lightpanda/spike/EmbeddedLightpandaIntegrationTest.java) exercises this behaviour with the real native library.

## Build the native library

The binding targets the **proposed embedding C ABI** from [Lightpanda PR #3096](https://github.com/lightpanda-io/browser/pull/3096), rather than the ordinary standalone Lightpanda executable. Installing an arbitrary Lightpanda release binary is **not enough**: you need a matching shared library exporting the `lp_*` functions.

The [spike findings](docs/embedding-spike.md) record a successfully tested upstream revision (`bbcc2f795d23d891dbbcd84f48773ea9be07fb63`) on Linux x86_64 with **Zig 0.16.0**. To reproduce that configuration, install the build prerequisites for that upstream revision, then:

```sh
git clone https://github.com/lightpanda-io/browser.git
cd browser
git checkout bbcc2f795d23d891dbbcd84f48773ea9be07fb63
zig build lib -Ddev_fast=false -Doptimize=ReleaseFast
```

Find the resulting `liblightpanda.so` and supply its absolute path through `LIGHTPANDA_LIBRARY` (or directly to `EmbeddedLightpanda.load(Path)`). Consult the upstream PR's build instructions if using a newer revision: the draft ABI and build prerequisites may have changed.

**Use `ReleaseFast`.** The default Debug library was observed to require static TLS and could not be loaded into an already-running JVM on Linux. The wrapper intentionally keeps the shared library mapped for the **JVM's lifetime** to avoid a crash seen when unloading it.

## Run the repository integration test

This project ships with the Gradle 9.2.1 Wrapper:

```sh
./gradlew test -PlightpandaLibrary=/absolute/path/to/liblightpanda.so
```

Alternatively, set the `LIGHTPANDA_LIBRARY` environment variable or `lightpanda.library` system property. The native integration test is **skipped** when no library path is provided. The Gradle test configuration already enables native access.

The test runs a local HTTP server and verifies navigation, JavaScript timer pumping, form filling, clicking, waiting for a result and extraction.

## Current restrictions

- **Experimental ABI:** The C API is still proposed upstream; this wrapper has no stable compatibility guarantee.
- **Single thread:** All calls to a browser and its sessions, including `close()` and `pump()`, must be made on the thread that created the browser. The current native ABI does not support freely parallel Java browser calls.
- **Single process lifecycle:** Lightpanda can be initialised only once per process; after `browser.close()` invokes `lp_shutdown`, the native V8 runtime cannot be reinitialised in the same JVM. Use a separate JVM process for independent repeated runs.
- **Native binary required:** No bundled native build, automatic download or cross-platform packaging is provided. Linux x86_64/JDK 25 was tested; JDK 22 is the minimum Java API level but its native integration has not been verified.
- **Tool-oriented interface:** Browser operations use tool names and JSON, not type-safe locators, promises or Playwright APIs. Lightpanda's support for web platform features is incomplete.
- **Licensing:** This repository is licensed under [AGPL-3.0-only](LICENSE); check the obligations for your intended use.

For the exact ABI assumptions, native memory lifetime, platform details and remaining work, see [`docs/embedding-spike.md`](docs/embedding-spike.md).

## Publishing

Pull requests targeting `main` are verified on JDK 22 and 25. Every push to `main` publishes a signed prerelease to Maven Central after both test runs pass. The version takes the form `0.1.0-main.<run-number>.<run-attempt>`; change `baseVersion` in `gradle.properties` to change the base. Publication requires the registered Sonatype Central namespace and the `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `MAVEN_SIGNING_KEY` and `MAVEN_SIGNING_PASSWORD` repository secrets. The signing key must be an ASCII-armored private GPG key, and Central credentials must be a Central Portal user-token pair.
