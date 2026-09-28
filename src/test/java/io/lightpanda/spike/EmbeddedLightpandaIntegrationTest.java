package io.lightpanda.spike;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedLightpandaIntegrationTest {
    @Test
    void navigatesAndInteractsWithAJavaScriptPageInProcess() throws IOException {
        String libraryPath = System.getProperty("lightpanda.library");
        Assumptions.assumeTrue(libraryPath != null && !libraryPath.isBlank(),
                "Set -Dlightpanda.library to a built liblightpanda to run the native integration test");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", EmbeddedLightpandaIntegrationTest::serveForm);
        server.start();

        try (EmbeddedLightpanda browser = EmbeddedLightpanda.load(Path.of(libraryPath));
             EmbeddedLightpanda.Session page = browser.newSession()) {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            page.call("goto", "{\"url\":\"" + url + "\"}");
            page.call("fill", "{\"selector\":\"#email\",\"value\":\"fred@example.com\"}");
            page.call("click", "{\"selector\":\"button[type=submit]\"}");
            page.call("waitForSelector", "{\"selector\":\"#welcome\"}");

            String extracted = page.call("extract",
                    "{\"schema\":\"{\\\"welcome\\\":\\\"#welcome\\\"}\"}");
            assertTrue(extracted.contains("Welcome fred@example.com"), extracted);
        } finally {
            server.stop(0);
        }
    }

    private static void serveForm(HttpExchange exchange) throws IOException {
        byte[] body = """
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
                """.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}
