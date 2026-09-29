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
            throw new IllegalStateException("lp_session_new failed: " + statusName(status)
                    + nativeError(lpBrowserLastError, browser));
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
        return "; Lightpanda error: " + decode(error, byteLength);
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
                    throw new IllegalStateException("Lightpanda tool '" + tool + "' failed: "
                            + statusName(status) + nativeError(lpLastError, handle));
                }

                long length = result.get(ValueLayout.JAVA_LONG, LENGTH_OFFSET);
                MemorySegment text = result.get(ValueLayout.ADDRESS, TEXT_OFFSET);
                String output = length == 0 || text.address() == 0 ? "" : decode(text, length);
                if (result.get(ValueLayout.JAVA_BOOLEAN, IS_ERROR_OFFSET)) {
                    throw new IllegalStateException("Lightpanda tool '" + tool
                            + "' returned an error: " + output);
                }
                return output;
            }
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
