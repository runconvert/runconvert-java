package com.runconvert;

/**
 * The API said no: {"success": false, "error": {"code", "message"}}. New codes can appear,
 * so branch on {@link #getStatus()} for anything you don't recognise.
 */
public class RunConvertException extends RuntimeException {
    private final int status;
    private final String code;
    private final String body;

    public RunConvertException(String message, int status, String code, String body) {
        super(message);
        this.status = status;
        this.code = code == null ? "" : code;
        this.body = body;
    }

    public RunConvertException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = "";
        this.body = null;
    }

    /** HTTP status, 0 when the request never got a response. */
    public int getStatus() { return status; }

    /** API error code, e.g. BILLING_INSUFFICIENT_CREDITS ("" when none). */
    public String getCode() { return code; }

    /** The raw response body, when there was one. */
    public String getBody() { return body; }
}
