package com.runconvert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * The RunConvert API client (v3).
 *
 * <pre>{@code
 * RunConvert rc = RunConvert.builder().build();   // reads RUNCONVERT_API_KEY
 * ConvertResult r = rc.convert(Path.of("report.docx"), ConvertOptions.to("pdf").outDir(Path.of("out")));
 * }</pre>
 */
public final class RunConvert {
    public static final String VERSION = "0.1.0";
    public static final String DEFAULT_BASE_URL = "https://api.runconvert.com/v3";
    private static final Set<Integer> RETRY_STATUS = Set.of(408, 425, 429, 500, 502, 503, 504);

    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    final HttpClient http;
    final String userAgent = "runconvert-java/" + VERSION + " java/" + System.getProperty("java.version");

    private final Jobs jobs = new Jobs(this);
    private final Operations operations = new Operations(this);
    private final Files files = new Files(this);

    private RunConvert(Builder b) {
        String key = b.apiKey != null ? b.apiKey : System.getenv("RUNCONVERT_API_KEY");
        this.apiKey = key == null ? "" : key;
        this.baseUrl = b.baseUrl.replaceAll("/+$", "");
        this.timeout = b.timeout;
        this.maxRetries = b.maxRetries;
        this.http = b.httpClient != null ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public static Builder builder() { return new Builder(); }

    /** Options for {@link RunConvert}. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private Duration timeout = Duration.ofSeconds(60);
        private int maxRetries = 2;
        private HttpClient httpClient;

        /** Defaults to the RUNCONVERT_API_KEY environment variable. */
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        /** Per API request (uploads and downloads have none). Default 60 s. */
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        /** Retries for network errors, 429 and 5xx. Default 2. */
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder httpClient(HttpClient httpClient) { this.httpClient = httpClient; return this; }
        public RunConvert build() { return new RunConvert(this); }
    }

    public Jobs jobs() { return jobs; }
    public Operations operations() { return operations; }
    public Files files() { return files; }

    /** The account, team and credit balance behind the API key. */
    public JsonNode me() {
        return request("GET", "/me", null, null).data;
    }

    /** Converts a local file in one call: upload, convert, wait, download (when outDir is set). */
    public ConvertResult convert(Path file, ConvertOptions o) {
        return convert(file.getFileName().toString(), null, file, o);
    }

    /** Converts a file from a public http(s) URL in one call: import, convert, wait, download. */
    public ConvertResult convert(URI url, ConvertOptions o) {
        String path = url.getPath() == null ? "" : url.getPath();
        return convert(path.substring(path.lastIndexOf('/') + 1), url.toString(), null, o);
    }

    private ConvertResult convert(String name, String sourceUrl, Path file, ConvertOptions o) {
        String from = o.from != null ? o.from : extension(name);
        if (from.isEmpty()) throw new IllegalArgumentException("Can't tell the source format: set ConvertOptions.from(\"mp4\")");
        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("import-1", sourceUrl != null ? Tasks.importUrl(sourceUrl) : Tasks.importUpload());
        tasks.put("convert-1", Tasks.convert(from.toLowerCase(Locale.ROOT), o.to.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""), "import-1", o.options));
        tasks.put("export-1", Tasks.exportUrl("convert-1"));
        Job job = jobs.create(tasks, o.tag, null);
        if (sourceUrl == null) {
            Task upload = job.task("import-1");
            if (upload == null || upload.upload_url == null) throw new RunConvertException("The job has no upload URL", 0, "", null);
            files.upload(upload.upload_url, file, name);
        }
        job = jobs.waitFor(job.id, o.timeout, o.onUpdate);
        List<ResultFile> results = job.exportedFiles();
        List<Path> saved = new ArrayList<>();
        if (o.outDir != null) for (ResultFile f : results) saved.add(files.download(f, o.outDir));
        return new ConvertResult(job, results, saved);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---- HTTP ----

    /** The unwrapped {@code data} and {@code meta} of a response. */
    static final class Result {
        final JsonNode data;
        final JsonNode meta;
        Result(JsonNode data, JsonNode meta) { this.data = data; this.meta = meta; }
    }

    <T> T convertValue(JsonNode node, Class<T> type) { return JSON.convertValue(node, type); }

    <T> T convertValue(JsonNode node, TypeReference<T> type) { return JSON.convertValue(node, type); }

    /** Sends a request and unwraps {"success": true, "data": …, "meta": …}. */
    Result request(String method, String path, Object body, Map<String, ?> query) {
        StringJoiner q = new StringJoiner("&");
        if (query != null) query.forEach((k, v) -> {
            if (v != null && !v.toString().isEmpty())
                q.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v.toString(), StandardCharsets.UTF_8));
        });
        URI uri = URI.create(baseUrl + path + (q.length() > 0 ? "?" + q : ""));
        byte[] payload;
        try {
            payload = body == null ? null : JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new RunConvertException("Can't encode the request", e);
        }
        // POST /jobs is only repeated when it never reached the server: a job is never created twice.
        boolean idempotent = !"POST".equals(method) || path.endsWith("/cancel");
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept", "application/json").header("User-Agent", userAgent)
                    .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload));
            if (payload != null) rb.header("Content-Type", "application/json");
            if (!apiKey.isEmpty()) rb.header("Authorization", "Bearer " + apiKey);
            HttpResponse<String> res;
            try {
                res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                boolean reached = e instanceof HttpTimeoutException;
                if (attempt < maxRetries && (idempotent || !reached)) { sleep(backoff(attempt)); continue; }
                throw new RunConvertException("Request to RunConvert failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RunConvertException("Interrupted", e);
            }
            int status = res.statusCode();
            if (RETRY_STATUS.contains(status) && attempt < maxRetries && (idempotent || status == 429)) {
                sleep(retryAfter(res).orElse(backoff(attempt)));
                continue;
            }
            if (status == 204) return new Result(null, null);
            JsonNode root = null;
            try {
                if (!res.body().isEmpty()) root = JSON.readTree(res.body());
            } catch (IOException ignored) {
                // not JSON
            }
            boolean failed = status >= 400 || (root != null && root.path("success").isBoolean() && !root.path("success").asBoolean());
            if (failed) {
                JsonNode err = root == null ? null : root.path("error");
                String message = err != null && err.hasNonNull("message") ? err.get("message").asText() : "RunConvert API error (HTTP " + status + ")";
                String code = err != null && err.hasNonNull("code") ? err.get("code").asText() : "";
                throw new RunConvertException(message, status, code, res.body());
            }
            return new Result(root == null ? null : root.get("data"), root == null ? null : root.get("meta"));
        }
    }

    /** Waits between retries and polls; package-private so tests can skip it. */
    void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunConvertException("Interrupted", e);
        }
    }

    private static Duration backoff(int attempt) {
        double ms = Math.min(8000, 500 * Math.pow(2, attempt)) * (0.75 + ThreadLocalRandom.current().nextDouble() / 2);
        return Duration.ofMillis((long) ms);
    }

    private static java.util.Optional<Duration> retryAfter(HttpResponse<?> res) {
        return res.headers().firstValue("retry-after").flatMap(v -> {
            try {
                long s = Long.parseLong(v.trim());
                return s > 0 ? java.util.Optional.of(Duration.ofSeconds(Math.min(s, 30))) : java.util.Optional.empty();
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        });
    }

    /** Options for {@link #convert}. */
    public static final class ConvertOptions {
        final String to;
        String from;
        Map<String, ?> options;
        Path outDir;
        String tag;
        Duration timeout;
        Consumer<Job> onUpdate;

        private ConvertOptions(String to) { this.to = to; }

        /** The target format, e.g. "mp3". */
        public static ConvertOptions to(String format) { return new ConvertOptions(format); }
        /** The source format; read from the file name or URL when not set. */
        public ConvertOptions from(String format) { this.from = format; return this; }
        /** Engine options, e.g. Map.of("audioBitrate", "192k"). */
        public ConvertOptions options(Map<String, ?> options) { this.options = options; return this; }
        /** Save results here (created if needed). Without it, nothing is downloaded. */
        public ConvertOptions outDir(Path dir) { this.outDir = dir; return this; }
        public ConvertOptions tag(String tag) { this.tag = tag; return this; }
        public ConvertOptions timeout(Duration timeout) { this.timeout = timeout; return this; }
        public ConvertOptions onUpdate(Consumer<Job> onUpdate) { this.onUpdate = onUpdate; return this; }
    }

    /** Result of {@link #convert}: the finished job, its result files, and local paths when outDir was set. */
    public static final class ConvertResult {
        public final Job job;
        public final List<ResultFile> files;
        public final List<Path> saved;

        ConvertResult(Job job, List<ResultFile> files, List<Path> saved) {
            this.job = job;
            this.files = files;
            this.saved = saved;
        }
    }
}
