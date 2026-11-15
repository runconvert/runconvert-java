package com.runconvert;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signature checks. Every delivery has an X-RunConvert-Signature header: "sha256=" + hex
 * HMAC-SHA256 of the raw request body, keyed with the endpoint's secret.
 */
public final class Webhooks {
    private Webhooks() {}

    /** True when the signature matches the raw body (pass it exactly as received). */
    public static boolean verify(byte[] rawBody, String signature, String secret) {
        if (signature == null || secret == null || secret.isEmpty()) return false;
        String given = signature.trim().toLowerCase(java.util.Locale.ROOT);
        if (given.startsWith("sha256=")) given = given.substring(7);
        if (!given.matches("[0-9a-f]{64}")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody);
            byte[] got = new byte[32];
            for (int i = 0; i < 32; i++) got[i] = (byte) Integer.parseInt(given.substring(i * 2, i * 2 + 2), 16);
            return MessageDigest.isEqual(expected, got);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
    }

    public static boolean verify(String rawBody, String signature, String secret) {
        return verify(rawBody.getBytes(StandardCharsets.UTF_8), signature, secret);
    }

    /** Verifies the signature, then parses the event ({"event", "job" | "task"}). Throws if it doesn't match. */
    public static JsonNode constructEvent(byte[] rawBody, String signature, String secret) {
        if (!verify(rawBody, signature, secret)) throw new IllegalArgumentException("Invalid RunConvert webhook signature");
        try {
            return RunConvert.JSON.readTree(rawBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("Webhook body is not JSON", e);
        }
    }
}
