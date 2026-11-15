package com.runconvert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builders for task definitions (plain maps, so any documented option can be added). */
public final class Tasks {
    private Tasks() {}

    /** Imports a file from a public http(s) URL. */
    public static Map<String, Object> importUrl(String sourceUrl) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("operation", "import/url");
        t.put("source_url", sourceUrl);
        return t;
    }

    /** An upload task; the created task carries upload_url (see {@link Files#upload}). */
    public static Map<String, Object> importUpload() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("operation", "import/direct/upload");
        return t;
    }

    /** Converts {@code input} (a task name, or a List of names to merge/combine). Options from {@link Operations#forPair}. */
    public static Map<String, Object> convert(String inputFormat, String outputFormat, Object input, Map<String, ?> options) {
        Map<String, Object> t = new LinkedHashMap<>();
        if (options != null) t.putAll(options);
        t.put("operation", "convert");
        t.put("input", input);
        t.put("input_format", inputFormat);
        t.put("output_format", outputFormat);
        return t;
    }

    /** Temporary download links for {@code input} (a task name or a List of names). */
    public static Map<String, Object> exportUrl(Object input) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("operation", "export/url");
        t.put("input", input instanceof List ? input : input.toString());
        return t;
    }
}
