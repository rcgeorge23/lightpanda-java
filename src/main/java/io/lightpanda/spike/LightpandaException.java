package io.lightpanda.spike;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Structured failure raised by {@link EmbeddedLightpanda.Session#call(String, String)}.
 *
 * <p>The message deliberately retains the historical {@code IllegalStateException} wording so
 * existing callers keep working. Additional native details are available through the accessors.
 * This type is part of the experimental spike API and may change with the native ABI.</p>
 */
public final class LightpandaException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    /** The kind of native failure that produced this exception. */
    public enum Kind {
        /** {@code lp_call} returned a non-zero native status. */
        CALL_FAILED,
        /** {@code lp_call} succeeded, but the tool reported an in-band error result. */
        TOOL_ERROR
    }

    private final Kind kind;
    private final String tool;
    private final Integer nativeStatus;
    private final String nativeStatusName;
    private final String nativeError;
    private final String toolResult;

    private LightpandaException(Kind kind, String tool, Integer nativeStatus,
                                String nativeStatusName, String nativeError,
                                String toolResult, String message) {
        super(message);
        this.kind = kind;
        this.tool = tool;
        this.nativeStatus = nativeStatus;
        this.nativeStatusName = nativeStatusName;
        this.nativeError = nativeError;
        this.toolResult = toolResult;
    }

    static LightpandaException callFailed(String tool, int nativeStatus, String nativeStatusName,
                                          String nativeError, String message) {
        return new LightpandaException(Kind.CALL_FAILED, tool, nativeStatus, nativeStatusName,
                emptyToNull(nativeError), null, message);
    }

    static LightpandaException toolError(String tool, String toolResult) {
        return new LightpandaException(Kind.TOOL_ERROR, tool, null, null, null, toolResult,
                "Lightpanda tool '" + tool + "' returned an error: " + toolResult);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** The kind of failure. */
    public Kind kind() {
        return kind;
    }

    /** The native tool name passed to {@code lp_call}. */
    public String tool() {
        return tool;
    }

    /** The native status code, when {@link #kind()} is {@link Kind#CALL_FAILED}. */
    public OptionalInt nativeStatus() {
        return nativeStatus == null ? OptionalInt.empty() : OptionalInt.of(nativeStatus);
    }

    /** The symbolic native status, when {@link #kind()} is {@link Kind#CALL_FAILED}. */
    public Optional<String> nativeStatusName() {
        return Optional.ofNullable(nativeStatusName);
    }

    /** The native error name returned by {@code lp_last_error}, when available. */
    public Optional<String> nativeError() {
        return Optional.ofNullable(nativeError);
    }

    /**
     * The in-band tool result, when {@link #kind()} is {@link Kind#TOOL_ERROR}.
     *
     * <p>This may contain page-derived diagnostic text. Treat it as untrusted content and avoid
     * logging it unless the caller has decided that the page content is safe to disclose.</p>
     */
    public Optional<String> toolResult() {
        return Optional.ofNullable(toolResult);
    }
}
