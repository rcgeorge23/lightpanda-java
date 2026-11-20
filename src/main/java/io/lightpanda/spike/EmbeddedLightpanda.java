package io.lightpanda.spike;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Experimental FFM binding to the C ABI proposed in Lightpanda PR #3096.
 * This is a spike, not a stable public API.
 */
public final class EmbeddedLightpanda implements AutoCloseable {
    private static final int LP_OK = 0;

    /** Prefix written by {@link Session#installJavascriptErrorCapture()} for uncaught errors. */
    public static final String UNCAUGHT_ERROR_MARKER = "[lightpanda-java:uncaught] ";

    /** Prefix written by {@link Session#installJavascriptErrorCapture()} for promise rejections. */
    public static final String UNHANDLED_REJECTION_MARKER =
            "[lightpanda-java:unhandled-rejection] ";

    private static final String NO_CONSOLE_MESSAGES = "No console messages.";

    private static final String ERROR_CAPTURE_SCRIPT = """
            (() => {
              if (window.__lightpandaJavaErrorCaptureInstalled) {
                return 'already installed';
              }
              window.__lightpandaJavaErrorCaptureInstalled = true;
              const format = value => {
                if (value instanceof Error) {
                  return value.stack || (String(value.name || 'Error') + ': ' + String(value.message || ''));
                }
                if (typeof value === 'string') {
                  return value;
                }
                try {
                  return JSON.stringify(value);
                } catch (_) {
                  return String(value);
                }
              };
              window.addEventListener('error', event => {
                if (event && event.error !== undefined && event.error !== null) {
                  console.error('%s' + format(event.error));
                } else if (event && event.message) {
                  console.error('%s' + event.message);
                }
              });
              window.addEventListener('unhandledrejection', event => {
                console.error('%s' + format(event.reason));
              });
              return 'installed';
            })()
            """.formatted(UNCAUGHT_ERROR_MARKER, UNCAUGHT_ERROR_MARKER, UNHANDLED_REJECTION_MARKER);

    /**
     * One message drained from the session's native console buffer.
     *
     * <p>Lightpanda's text-oriented console tool does not expose its native timestamp, source URL,
     * line or column. Messages are therefore associated with a session but not with a particular
     * navigation or script location.</p>
     */
    public record ConsoleMessage(Type type, Origin origin, String text) {
        public ConsoleMessage {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(text, "text");
        }

        /** Console severity reported by Lightpanda. */
        public enum Type {
            LOG,
            DEBUG,
            INFO,
            WARNING,
            ERROR,
            FATAL,
            TRACE,
            DIR,
            DIRXML,
            UNKNOWN
        }

        /** Where the message came from. */
        public enum Origin {
            /** A direct {@code console.*} call. */
            CONSOLE,
            /** An error forwarded by {@link Session#installJavascriptErrorCapture()}. */
            UNCAUGHT_EXCEPTION,
            /** A rejection forwarded by {@link Session#installJavascriptErrorCapture()}. */
            UNHANDLED_REJECTION
        }

        /** Whether this message is an error-level console message. */
        public boolean isErrorLevel() {
            return type == Type.ERROR || type == Type.FATAL;
        }

        /** Whether this message was captured from a JavaScript exception or rejection. */
        public boolean isJavascriptError() {
            return origin != Origin.CONSOLE;
        }
    }

    private static final GroupLayout RESULT_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("text"),
            ValueLayout.JAVA_LONG.withName("len"),
            ValueLayout.JAVA_BOOLEAN.withName("is_error"),
            MemoryLayout.paddingLayout(7));
    private static final long TEXT_OFFSET =
            RESULT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("text"));
    private static final long LENGTH_OFFSET =
            RESULT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("len"));
    private static final long IS_ERROR_OFFSET =
            RESULT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("is_error"));

    private final Thread ownerThread;
    private final Arena arena;
    private final MethodHandle lpShutdown;
    private final MethodHandle lpSessionNew;
    private final MethodHandle lpSessionClose;
    private final MethodHandle lpCall;
    private final MethodHandle lpSessionPump;
    private final MethodHandle lpBrowserLastError;
    private final MethodHandle lpLastError;
    private final MemorySegment browser;
    private final List<Session> sessions = new ArrayList<>();
    private boolean closed;

    public static EmbeddedLightpanda load(Path libraryPath) {
        return new EmbeddedLightpanda(libraryPath);
    }

    private EmbeddedLightpanda(Path libraryPath) {
        Objects.requireNonNull(libraryPath, "libraryPath");
        if (ValueLayout.ADDRESS.byteSize() != Long.BYTES) {
            throw new UnsupportedOperationException("This spike requires a 64-bit process");
        }

        ownerThread = Thread.currentThread();
        arena = Arena.ofConfined();
        try {
            // Unloading after lp_shutdown caused a JVM teardown crash; keep
            // the mapping alive for the JVM lifetime.
            SymbolLookup symbols = SymbolLookup.libraryLookup(libraryPath, Arena.global());
            Linker linker = Linker.nativeLinker();

            MethodHandle lpInit = bind(symbols, linker, "lp_init",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            lpShutdown = bind(symbols, linker, "lp_shutdown",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            lpSessionNew = bind(symbols, linker, "lp_session_new",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            lpSessionClose = bind(symbols, linker, "lp_session_close",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            lpCall = bind(symbols, linker, "lp_call",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS));
            lpSessionPump = bind(symbols, linker, "lp_session_pump",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            lpBrowserLastError = bind(symbols, linker, "lp_browser_last_error",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            lpLastError = bind(symbols, linker, "lp_last_error",
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            MemorySegment browserOut = arena.allocate(ValueLayout.ADDRESS);
            browserOut.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            int status = invokeInt(lpInit, MemorySegment.NULL, browserOut);
            if (status != LP_OK) {
                throw new IllegalStateException("lp_init failed: " + statusName(status));
            }
            browser = browserOut.get(ValueLayout.ADDRESS, 0);
            if (browser.address() == 0) {
                throw new IllegalStateException("lp_init returned a null browser handle");
            }
        } catch (RuntimeException | Error failure) {
            arena.close();
            throw failure;
        }
    }

    public Session newSession() {
        requireOpen();
        MemorySegment sessionOut = arena.allocate(ValueLayout.ADDRESS);
        sessionOut.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);

        int status = invokeInt(lpSessionNew, browser, sessionOut);
        if (status != LP_OK) {
            String nativeError = nativeError(lpBrowserLastError, browser);
            throw new IllegalStateException("lp_session_new failed: " + statusName(status)
                    + nativeErrorSuffix(nativeError));
        }

        MemorySegment handle = sessionOut.get(ValueLayout.ADDRESS, 0);
        if (handle.address() == 0) {
            throw new IllegalStateException("lp_session_new returned a null session handle");
        }
        Session session = new Session(handle);
        sessions.add(session);
        return session;
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (closed) {
            return;
        }

        for (Session session : List.copyOf(sessions)) {
            session.close();
        }
        invokeVoid(lpShutdown, browser);
        closed = true;
        arena.close();
    }

    private void requireOpen() {
        requireOwnerThread();
        if (closed) {
            throw new IllegalStateException("Lightpanda is closed");
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "Lightpanda handles must only be used from the thread that initialized it");
        }
    }

    private String nativeError(MethodHandle errorFunction, MemorySegment handle) {
        MemorySegment length = arena.allocate(ValueLayout.JAVA_LONG);
        length.set(ValueLayout.JAVA_LONG, 0, 0L);
        MemorySegment error = invokeAddress(errorFunction, handle, length);
        long byteLength = length.get(ValueLayout.JAVA_LONG, 0);
        if (error.address() == 0 || byteLength <= 0) {
            return "";
        }
        return decode(error, byteLength);
    }

    private static String nativeErrorSuffix(String nativeError) {
        return nativeError == null || nativeError.isEmpty()
                ? ""
                : "; Lightpanda error: " + nativeError;
    }

    private static MethodHandle bind(SymbolLookup symbols, Linker linker,
                                     String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = symbols.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Missing Lightpanda symbol: " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    private static int invokeInt(MethodHandle function, Object... arguments) {
        try {
            return (int) function.invokeWithArguments(arguments);
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    private static MemorySegment invokeAddress(MethodHandle function, Object... arguments) {
        try {
            return (MemorySegment) function.invokeWithArguments(arguments);
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    private static void invokeVoid(MethodHandle function, Object... arguments) {
        try {
            function.invokeWithArguments(arguments);
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    private static RuntimeException invocationFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("FFM call to Lightpanda failed", failure);
    }

    private static String statusName(int status) {
        return switch (status) {
            case 1 -> "LP_ERR_INVALID_PARAMS";
            case 2 -> "LP_ERR_FRAME_NOT_LOADED";
            case 3 -> "LP_ERR_NODE_NOT_FOUND";
            case 4 -> "LP_ERR_NAVIGATION_FAILED";
            case 5 -> "LP_ERR_CANCELLED";
            case 6 -> "LP_ERR_TIMEOUT";
            case 7 -> "LP_ERR_OUT_OF_MEMORY";
            case 8 -> "LP_ERR_INTERNAL";
            case 9 -> "LP_ERR_MISUSE";
            default -> "unknown status " + status;
        };
    }

    private static String decode(MemorySegment address, long length) {
        if (length > Integer.MAX_VALUE) {
            throw new IllegalStateException("Native result is too large: " + length + " bytes");
        }
        byte[] bytes = address.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String evaluateArguments(String script) {
        return "{\"script\":" + jsonString(script) + "}";
    }

    private static String jsonString(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2);
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    private static List<ConsoleMessage> parseConsoleMessages(String raw) {
        if (raw == null || raw.isEmpty() || NO_CONSOLE_MESSAGES.equals(raw)) {
            return List.of();
        }

        List<ConsoleMessage> messages = new ArrayList<>();
        ConsoleMessage.Type currentType = null;
        StringBuilder currentText = new StringBuilder();
        int lineStart = 0;
        while (lineStart < raw.length()) {
            int newline = raw.indexOf('\n', lineStart);
            boolean lastLine = newline < 0;
            String line = lastLine ? raw.substring(lineStart) : raw.substring(lineStart, newline);

            int prefixEnd = consolePrefixEnd(line);
            if (prefixEnd > 0) {
                if (currentType != null) {
                    messages.add(consoleMessage(currentType, currentText.toString()));
                }
                currentType = consoleType(line.substring(1, prefixEnd - 2));
                currentText.setLength(0);
                currentText.append(line.substring(prefixEnd));
            } else if (currentType != null) {
                currentText.append('\n').append(line);
            } else if (!line.isEmpty()) {
                messages.add(consoleMessage(ConsoleMessage.Type.UNKNOWN, line));
            }

            if (lastLine) {
                break;
            }
            lineStart = newline + 1;
        }
        if (currentType != null) {
            messages.add(consoleMessage(currentType, currentText.toString()));
        }
        return List.copyOf(messages);
    }

    private static int consolePrefixEnd(String line) {
        if (line.length() < 4 || line.charAt(0) != '[') {
            return -1;
        }
        int close = line.indexOf("] ");
        return close > 1 ? close + 2 : -1;
    }

    private static ConsoleMessage.Type consoleType(String name) {
        return switch (name) {
            case "log" -> ConsoleMessage.Type.LOG;
            case "debug" -> ConsoleMessage.Type.DEBUG;
            case "info" -> ConsoleMessage.Type.INFO;
            case "warning" -> ConsoleMessage.Type.WARNING;
            case "error" -> ConsoleMessage.Type.ERROR;
            case "fatal" -> ConsoleMessage.Type.FATAL;
            case "trace" -> ConsoleMessage.Type.TRACE;
            case "dir" -> ConsoleMessage.Type.DIR;
            case "dirxml" -> ConsoleMessage.Type.DIRXML;
            default -> ConsoleMessage.Type.UNKNOWN;
        };
    }

    private static ConsoleMessage consoleMessage(ConsoleMessage.Type type, String text) {
        String message = text;
        ConsoleMessage.Origin origin = ConsoleMessage.Origin.CONSOLE;
        if (message.startsWith(UNCAUGHT_ERROR_MARKER)) {
            origin = ConsoleMessage.Origin.UNCAUGHT_EXCEPTION;
            message = message.substring(UNCAUGHT_ERROR_MARKER.length());
        } else if (message.startsWith(UNHANDLED_REJECTION_MARKER)) {
            origin = ConsoleMessage.Origin.UNHANDLED_REJECTION;
            message = message.substring(UNHANDLED_REJECTION_MARKER.length());
        }
        return new ConsoleMessage(type, origin, message);
    }

    private record Utf8(MemorySegment address, long length) {
        private static Utf8 allocate(Arena arena, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return new Utf8(arena.allocateFrom(value, StandardCharsets.UTF_8), bytes.length);
        }
    }

    public final class Session implements AutoCloseable {
        private final MemorySegment handle;
        private boolean sessionClosed;

        private Session(MemorySegment handle) {
            this.handle = handle;
        }

        /** Calls one tool exposed by lp_tools_json and returns its copied UTF-8 result. */
        public String call(String tool, String argumentsJson) {
            requireOpen();
            if (sessionClosed) {
                throw new IllegalStateException("Lightpanda session is closed");
            }
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(argumentsJson, "argumentsJson");

            try (Arena callArena = Arena.ofConfined()) {
                Utf8 nativeTool = Utf8.allocate(callArena, tool);
                Utf8 nativeArguments = Utf8.allocate(callArena, argumentsJson);
                MemorySegment result = callArena.allocate(RESULT_LAYOUT);
                result.set(ValueLayout.ADDRESS, TEXT_OFFSET, MemorySegment.NULL);
                result.set(ValueLayout.JAVA_LONG, LENGTH_OFFSET, 0L);
                result.set(ValueLayout.JAVA_BOOLEAN, IS_ERROR_OFFSET, false);

                int status = invokeInt(lpCall, handle,
                        nativeTool.address(), nativeTool.length(),
                        nativeArguments.address(), nativeArguments.length(), result);
                if (status != LP_OK) {
                    String statusName = statusName(status);
                    String nativeError = nativeError(lpLastError, handle);
                    throw LightpandaException.callFailed(tool, status, statusName, nativeError,
                            "Lightpanda tool '" + tool + "' failed: " + statusName
                                    + nativeErrorSuffix(nativeError));
                }

                long length = result.get(ValueLayout.JAVA_LONG, LENGTH_OFFSET);
                MemorySegment text = result.get(ValueLayout.ADDRESS, TEXT_OFFSET);
                String output = length == 0 || text.address() == 0 ? "" : decode(text, length);
                if (result.get(ValueLayout.JAVA_BOOLEAN, IS_ERROR_OFFSET)) {
                    throw LightpandaException.toolError(tool, output);
                }
                return output;
            }
        }

        /**
         * Drains and clears this session's native console buffer.
         *
         * <p>Lightpanda captures direct {@code console.*} calls per session. Messages forwarded by
         * {@link #installJavascriptErrorCapture()} are also returned, with an origin identifying
         * the exception or rejection rather than a direct console call.</p>
         *
         * @return an immutable snapshot of the buffered messages; empty when nothing is buffered
         */
        public List<ConsoleMessage> drainConsoleMessages() {
            return parseConsoleMessages(call("consoleLogs", "{}"));
        }

        /**
         * Installs page-level listeners that forward uncaught errors and unhandled promise
         * rejections to {@code console.error}, making them available through
         * {@link #drainConsoleMessages()}.
         *
         * <p>Lightpanda's native C ABI does not expose these exceptions as events. This helper is a
         * page-level shim, not native capture. Call it again after every navigation, because the
         * listeners belong to the current document. Errors raised before installation, during page
         * load before the shim runs, and inside iframes or workers are not captured. Rejection
         * events may include failures from page resource requests, so callers that fail tests on
         * JavaScript errors should consider whether those are meaningful for their application.</p>
         *
         * <p>Calling this method repeatedly on the same document is safe; the underlying listener
         * installation is idempotent.</p>
         */
        public void installJavascriptErrorCapture() {
            call("evaluate", evaluateArguments(ERROR_CAPTURE_SCRIPT));
        }

        /**
         * Runs one slice of this session's background work and returns the number of milliseconds
         * Lightpanda recommends sleeping before pumping again. The call must stay on the browser's
         * initializing thread, like every other native session operation.
         *
         * @return the unsigned native delay in milliseconds
         */
        public long pump() {
            requireOpen();
            if (sessionClosed) {
                throw new IllegalStateException("Lightpanda session is closed");
            }
            return Integer.toUnsignedLong(invokeInt(lpSessionPump, handle));
        }

        @Override
        public void close() {
            requireOwnerThread();
            if (sessionClosed) {
                return;
            }
            if (!closed) {
                invokeVoid(lpSessionClose, handle);
            }
            sessionClosed = true;
            sessions.remove(this);
        }
    }
}
