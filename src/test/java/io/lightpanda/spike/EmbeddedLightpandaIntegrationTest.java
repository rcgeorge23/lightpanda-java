package io.lightpanda.spike;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedLightpandaIntegrationTest {
    @Test
    void navigatesInteractsAndExposesSessionDiagnostics() throws IOException, InterruptedException {
        String libraryPath = System.getProperty("lightpanda.library");
        Assumptions.assumeTrue(libraryPath != null && !libraryPath.isBlank(),
                "Set -Dlightpanda.library to a built liblightpanda to run the native integration test");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", EmbeddedLightpandaIntegrationTest::serveForm);
        server.createContext("/console/first", exchange -> serveConsole(exchange, "first-session"));
        server.createContext("/console/second", exchange -> serveConsole(exchange, "second-session"));
        server.createContext("/errors", EmbeddedLightpandaIntegrationTest::serveErrors);
        server.start();

        try (EmbeddedLightpanda browser = EmbeddedLightpanda.load(Path.of(libraryPath));
             EmbeddedLightpanda.Session page = browser.newSession()) {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            page.call("goto", "{\"url\":\"" + baseUrl + "/\"}");
            page.call("evaluate", "{\"script\":\"setTimeout(() => document.body.dataset.pumped = 'yes', 20); 'scheduled'\"}");
            long pumpDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean timerFired = false;
            while (System.nanoTime() < pumpDeadline) {
                long sleepMillis = page.pump();
                Thread.sleep(Math.max(1, Math.min(sleepMillis, 10)));
                String timerState = page.call("evaluate", "{\"script\":\"document.body.dataset.pumped === 'yes'\"}");
                if (timerState.contains("true")) {
                    timerFired = true;
                    break;
                }
            }
            assertTrue(timerFired, "session pumping should run JavaScript timers");
            page.call("fill", "{\"selector\":\"#email\",\"value\":\"fred@example.com\"}");
            page.call("click", "{\"selector\":\"button[type=submit]\"}");
            page.call("waitForSelector", "{\"selector\":\"#welcome\"}");

            String extracted = page.call("extract",
                    "{\"schema\":\"{\\\"welcome\\\":\\\"#welcome\\\"}\"}");
            assertTrue(extracted.contains("Welcome fred@example.com"), extracted);

            page.call("goto", "{\"url\":\"" + baseUrl + "/console/first\"}");
            List<EmbeddedLightpanda.ConsoleMessage> messages = page.drainConsoleMessages();
            assertTrue(hasMessage(messages, EmbeddedLightpanda.ConsoleMessage.Type.ERROR,
                    EmbeddedLightpanda.ConsoleMessage.Origin.CONSOLE, "first-session"), messages.toString());
            assertTrue(page.drainConsoleMessages().isEmpty(),
                    "draining the console buffer should clear it");

            page.call("goto", "{\"url\":\"" + baseUrl + "/errors\"}");
            page.installJavascriptErrorCapture();
            page.installJavascriptErrorCapture();

            page.call("click", "{\"selector\":\"#listener\"}");
            messages = drainUntil(page, message -> message.origin()
                    == EmbeddedLightpanda.ConsoleMessage.Origin.UNCAUGHT_EXCEPTION
                    && message.text().contains("listener-boom"));
            assertEquals(1, count(messages, message -> message.origin()
                    == EmbeddedLightpanda.ConsoleMessage.Origin.UNCAUGHT_EXCEPTION
                    && message.text().contains("listener-boom")), messages.toString());

            page.call("click", "{\"selector\":\"#reject\"}");
            messages = drainUntil(page, message -> message.origin()
                    == EmbeddedLightpanda.ConsoleMessage.Origin.UNHANDLED_REJECTION
                    && message.text().contains("reject-boom"));
            assertTrue(messages.stream().anyMatch(message -> message.origin()
                    == EmbeddedLightpanda.ConsoleMessage.Origin.UNHANDLED_REJECTION
                    && message.text().contains("reject-boom")), messages.toString());

            try (EmbeddedLightpanda.Session second = browser.newSession()) {
                assertTrue(second.drainConsoleMessages().isEmpty(),
                        "a new session must not inherit another session's console buffer");

                second.call("goto", "{\"url\":\"" + baseUrl + "/console/second\"}");
                page.call("goto", "{\"url\":\"" + baseUrl + "/console/first\"}");

                List<EmbeddedLightpanda.ConsoleMessage> firstMessages = page.drainConsoleMessages();
                List<EmbeddedLightpanda.ConsoleMessage> secondMessages = second.drainConsoleMessages();
                assertTrue(hasText(firstMessages, "first-session"), firstMessages.toString());
                assertFalse(hasText(firstMessages, "second-session"), firstMessages.toString());
                assertTrue(hasText(secondMessages, "second-session"), secondMessages.toString());
                assertFalse(hasText(secondMessages, "first-session"), secondMessages.toString());
            }

            LightpandaException clickFailure = assertThrows(LightpandaException.class,
                    () -> page.call("click", "{\"selector\":\"#does-not-exist\"}"));
            assertEquals(LightpandaException.Kind.CALL_FAILED, clickFailure.kind());
            assertEquals("click", clickFailure.tool());
            assertEquals(OptionalInt.of(3), clickFailure.nativeStatus());
            assertEquals(Optional.of("LP_ERR_NODE_NOT_FOUND"), clickFailure.nativeStatusName());
            assertEquals(Optional.of("NodeNotFound"), clickFailure.nativeError());
            assertTrue(clickFailure.getMessage().contains("LP_ERR_NODE_NOT_FOUND"),
                    clickFailure.getMessage());
            assertTrue(clickFailure.getMessage().contains("NodeNotFound"), clickFailure.getMessage());

            LightpandaException evaluateFailure = assertThrows(LightpandaException.class,
                    () -> page.call("evaluate", "{\"script\":\"throw new Error('eval-boom')\"}"));
            assertEquals(LightpandaException.Kind.TOOL_ERROR, evaluateFailure.kind());
            assertEquals("evaluate", evaluateFailure.tool());
            assertTrue(evaluateFailure.nativeStatus().isEmpty());
            assertTrue(evaluateFailure.nativeStatusName().isEmpty());
            assertTrue(evaluateFailure.nativeError().isEmpty());
            assertTrue(evaluateFailure.toolResult().orElseThrow().contains("eval-boom"),
                    evaluateFailure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    private static List<EmbeddedLightpanda.ConsoleMessage> drainUntil(
            EmbeddedLightpanda.Session session,
            Predicate<EmbeddedLightpanda.ConsoleMessage> condition) throws InterruptedException {
        List<EmbeddedLightpanda.ConsoleMessage> drained = new ArrayList<>();
        for (int attempt = 0; attempt < 50; attempt++) {
            drained.addAll(session.drainConsoleMessages());
            if (drained.stream().anyMatch(condition)) {
                return drained;
            }
            long delayMillis = session.pump();
            if (delayMillis > 0) {
                Thread.sleep(Math.min(delayMillis, 10));
            }
        }
        return drained;
    }

    private static long count(List<EmbeddedLightpanda.ConsoleMessage> messages,
                              Predicate<EmbeddedLightpanda.ConsoleMessage> condition) {
        return messages.stream().filter(condition).count();
    }

    private static boolean hasMessage(List<EmbeddedLightpanda.ConsoleMessage> messages,
                                      EmbeddedLightpanda.ConsoleMessage.Type type,
                                      EmbeddedLightpanda.ConsoleMessage.Origin origin,
                                      String text) {
        return messages.stream().anyMatch(message -> message.type() == type
                && message.origin() == origin && message.text().contains(text));
    }

    private static boolean hasText(List<EmbeddedLightpanda.ConsoleMessage> messages, String text) {
        return messages.stream().anyMatch(message -> message.text().contains(text));
    }

    private static void serveForm(HttpExchange exchange) throws IOException {
        serveHtml(exchange, """
                <!doctype html>
                <html><body>
                  <form id="login">
                    <label for="email">Email</label>
                    <input id="email" type="email">
                    <button type="submit">Login</button>
                  </form>
                  <script>
                    document.querySelector('#login').addEventListener('submit', event => {
                      event.preventDefault();
                      const welcome = document.createElement('p');
                      welcome.id = 'welcome';
                      welcome.textContent = 'Welcome ' + document.querySelector('#email').value;
                      document.body.append(welcome);
                    });
                  </script>
                </body></html>
                """);
    }

    private static void serveConsole(HttpExchange exchange, String marker) throws IOException {
        serveHtml(exchange, """
                <!doctype html>
                <html><body>
                  <script>console.error('%s');</script>
                </body></html>
                """.formatted(marker));
    }

    private static void serveErrors(HttpExchange exchange) throws IOException {
        serveHtml(exchange, """
                <!doctype html>
                <html><body>
                  <button id="listener">listener</button>
                  <button id="reject">reject</button>
                  <script>
                    document.querySelector('#listener').addEventListener('click', () => {
                      throw new Error('listener-boom');
                    });
                    document.querySelector('#reject').addEventListener('click', () => {
                      Promise.reject(new Error('reject-boom'));
                    });
                  </script>
                </body></html>
                """);
    }

    private static void serveHtml(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try {
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}
