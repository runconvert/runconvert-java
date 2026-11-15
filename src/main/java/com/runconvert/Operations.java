package com.runconvert;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Formats, targets, engines and options. Public: no key needed. */
public final class Operations {
    private final RunConvert c;

    Operations(RunConvert c) { this.c = c; }

    /** Every input format with its targets: {"categories": [...], "formats": [...]}. category may be null. */
    public JsonNode list(String category) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("category", category);
        return c.request("GET", "/operations", null, q).data;
    }

    /** What one format converts to, with credit costs: {"format", "outputs": [...]}. */
    public JsonNode forInput(String input) {
        return c.request("GET", "/operations", null, Map.of("input", input.toLowerCase(Locale.ROOT))).data;
    }

    /** Engines for one conversion and the options a convert task accepts: {"engines": [...]}. */
    public JsonNode forPair(String input, String output) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("input", input.toLowerCase(Locale.ROOT));
        q.put("output", output.toLowerCase(Locale.ROOT));
        return c.request("GET", "/operations", null, q).data;
    }
}
