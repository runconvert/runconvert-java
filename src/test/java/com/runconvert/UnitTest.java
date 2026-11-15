package com.runconvert;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnitTest {
    /** A recorded request. */
    record Call(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        String q(String key) {
            if (query == null) return null;
            for (String p : query.split("&")) {
                String[] kv = p.split("=", 2);
                if (java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8).equals(key)) return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
            return null;
        }
    }

    /** A response: status, body, extra headers. */
    record Reply(int status, String body, Map<String, String> headers) {
        static Reply ok(String data) { return new Reply(200, "{\"success\":true,\"data\":" + data + "}", Map.of()); }
        static Reply okMeta(String data, String meta) { return new Reply(200, "{\"success\":true,\"data\":" + data + ",\"meta\":" + meta + "}", Map.of()); }
        static Reply fail(int status, String code, String msg) { return new Reply(status, "{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + msg + "\"}}", Map.of()); }
    }

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    private String start(BiFunction<Call, Integer, Reply> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange ex) -> {
            Call call = new Call(ex.getRequestMethod(), ex.getRequestURI().getPath().replaceFirst("^/v3", ""), ex.getRequestURI().getRawQuery(), ex.getRequestHeaders(), ex.getRequestBody().readAllBytes());
            calls.add(call);
            Reply r = handler.apply(call, calls.size());
            r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            if (r.status() == 204) { ex.sendResponseHeaders(204, -1); ex.close(); return; }
            ex.sendResponseHeaders(r.status(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private RunConvert client(String base) {
        return RunConvert.builder().apiKey("rc_live_test").baseUrl(base + "/v3").maxRetries(2).build();
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void sendsKeyAndUnwrapsEnvelope() throws IOException {
        RunConvert rc = client(start((c, n) -> Reply.ok("{\"credits\":{\"total_remaining\":42}}")));
        assertEquals(42, rc.me().path("credits").path("total_remaining").asInt());
        assertEquals("Bearer rc_live_test", calls.get(0).headers().get("Authorization").get(0));
        assertTrue(calls.get(0).headers().get("User-agent").get(0).startsWith("runconvert-java/"));
    }

    @Test void apiErrorsAreTyped() throws IOException {
        RunConvert rc = client(start((c, n) -> Reply.fail(422, "BILLING_INSUFFICIENT_CREDITS", "Insufficient credits.")));
        RunConvertException e = assertThrows(RunConvertException.class, () -> rc.jobs().get("job_1"));
        assertEquals(422, e.getStatus());
        assertEquals("BILLING_INSUFFICIENT_CREDITS", e.getCode());
        assertEquals("Insufficient credits.", e.getMessage());
    }

    @Test void retriesGetButNeverDuplicatesAJob() throws IOException {
        RunConvert rc = client(start((c, n) -> n < 3 ? new Reply(503, "busy", Map.of()) : Reply.ok("{\"id\":\"job_1\",\"status\":\"finished\"}")));
        assertEquals("finished", rc.jobs().get("job_1").status);
        assertEquals(3, calls.size());
        stop(); calls.clear();
        RunConvert rc2 = client(start((c, n) -> Reply.fail(500, "INTERNAL", "boom")));
        assertThrows(RunConvertException.class, () -> rc2.jobs().create(Map.of()));
        assertEquals(1, calls.size());
        stop(); calls.clear();
        RunConvert rc3 = client(start((c, n) -> n == 1 ? new Reply(429, "{\"success\":false,\"error\":{\"code\":\"ACTIVE_JOB_LIMIT_REACHED\",\"message\":\"x\"}}", Map.of("Retry-After", "1")) : Reply.ok("{\"id\":\"job_1\",\"status\":\"waiting\"}")));
        assertEquals("job_1", rc3.jobs().create(Map.of()).id);
        assertEquals(2, calls.size());
        assertEquals("tasks", calls.get(1).q("include"));
    }

    @Test void listAndAll() throws IOException {
        RunConvert rc = client(start((c, n) -> Reply.okMeta("[{\"id\":\"job_p" + c.q("page") + "\",\"status\":\"finished\"}]", "{\"page\":" + c.q("page") + ",\"total_pages\":2}")));
        Jobs.Page page = rc.jobs().list("finished", "x", 1, 1);
        assertEquals(2, page.totalPages);
        assertEquals("finished", calls.get(0).q("filter[status]"));
        assertEquals("x", calls.get(0).q("filter[tag]"));
        List<String> ids = new ArrayList<>();
        for (Job j : rc.jobs().all(null, null, 1)) ids.add(j.id);
        assertEquals(List.of("job_p1", "job_p2"), ids);
    }

    @Test void waitForFinishedFailedAndTimeout() throws IOException {
        String[] states = {"waiting", "processing", "finished"};
        RunConvert rc = client(start((c, n) -> Reply.ok("{\"id\":\"job_1\",\"status\":\"" + states[Math.min(n - 1, 2)] + "\"}")));
        List<String> seen = new ArrayList<>();
        assertEquals("finished", rc.jobs().waitFor("job_1", null, j -> seen.add(j.status)).status);
        assertEquals(List.of(states), seen);
        stop(); calls.clear();
        RunConvert rc2 = client(start((c, n) -> Reply.ok("{\"id\":\"job_1\",\"status\":\"error\",\"tasks\":[{\"name\":\"c\",\"operation\":\"convert\",\"status\":\"error\",\"code\":\"INVALID_DATA\",\"reason\":\"INPUT_EMPTY\",\"message\":\"The file is empty.\"}]}")));
        JobFailedException e = assertThrows(JobFailedException.class, () -> rc2.jobs().waitFor("job_1"));
        assertEquals("INPUT_EMPTY", e.getTask().reason);
        assertEquals("The file is empty.", e.getMessage());
        stop(); calls.clear();
        RunConvert rc3 = client(start((c, n) -> Reply.ok("{\"id\":\"job_1\",\"status\":\"processing\"}")));
        assertThrows(WaitTimeoutException.class, () -> rc3.jobs().waitFor("job_1", Duration.ofMillis(1), null));
    }

    @Test void deleteAndOperations() throws IOException {
        RunConvert rc = client(start((c, n) -> c.method().equals("DELETE") ? new Reply(204, "", Map.of()) : Reply.ok("{\"engines\":[]}")));
        rc.jobs().delete("job_1");
        rc.operations().forPair("MP4", "MP3");
        assertEquals("mp4", calls.get(1).q("input"));
        assertEquals("mp3", calls.get(1).q("output"));
    }

    @Test void webhookSignatures() throws Exception {
        String body = "{\"event\":\"job.finished\",\"job\":{\"id\":\"job_1\"}}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_x".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
        String sig = "sha256=" + hex;
        assertTrue(Webhooks.verify(body, sig, "whsec_x"));
        assertFalse(Webhooks.verify(body + " ", sig, "whsec_x"));
        assertFalse(Webhooks.verify(body, sig, "other"));
        assertFalse(Webhooks.verify(body, "sha256=nothex", "whsec_x"));
        assertFalse(Webhooks.verify(body, null, "whsec_x"));
        JsonNode ev = Webhooks.constructEvent(body.getBytes(StandardCharsets.UTF_8), sig, "whsec_x");
        assertEquals("job_1", ev.path("job").path("id").asText());
        assertThrows(IllegalArgumentException.class, () -> Webhooks.constructEvent(body.getBytes(StandardCharsets.UTF_8), "sha256=" + "0".repeat(64), "whsec_x"));
    }

    @Test void convertUploadsWaitsAndDownloads(@TempDir Path dir) throws IOException {
        AtomicInteger polls = new AtomicInteger();
        String[] base = new String[1];
        base[0] = start((c, n) -> {
            if (c.method().equals("POST") && c.path().equals("/jobs"))
                return Reply.ok("{\"id\":\"job_1\",\"status\":\"waiting\",\"tasks\":[{\"name\":\"import-1\",\"operation\":\"import/direct/upload\",\"status\":\"waiting\",\"upload_url\":\"" + base[0] + "/upload?token=t\"}]}");
            if (c.method().equals("PUT")) return new Reply(200, "", Map.of());
            if (c.path().equals("/dl/notes.pdf")) return new Reply(200, "PDF", Map.of());
            String status = polls.incrementAndGet() > 1 ? "finished" : "processing";
            return Reply.ok("{\"id\":\"job_1\",\"status\":\"" + status + "\",\"tasks\":[{\"name\":\"export-1\",\"operation\":\"export/url\",\"status\":\"finished\",\"result\":{\"files\":[{\"filename\":\"notes.pdf\",\"url\":\"" + base[0] + "/dl/notes.pdf\"}]}}]}");
        });
        Path src = dir.resolve("notes.txt");
        java.nio.file.Files.writeString(src, "hello");
        RunConvert.ConvertResult res = client(base[0]).convert(src, RunConvert.ConvertOptions.to("pdf").outDir(dir.resolve("out")).options(Map.of("page_size", "a4")));
        JsonNode created = RunConvert.JSON.readTree(calls.get(0).body()).path("tasks").path("convert-1");
        assertEquals("txt", created.path("input_format").asText());
        assertEquals("pdf", created.path("output_format").asText());
        assertEquals("a4", created.path("page_size").asText());
        Call put = calls.stream().filter(c -> c.method().equals("PUT")).findFirst().orElseThrow();
        assertEquals("notes.txt", put.q("filename"));
        assertEquals("t", put.q("token"));
        assertEquals("hello", new String(put.body(), StandardCharsets.UTF_8));
        assertEquals("PDF", java.nio.file.Files.readString(res.saved.get(0)));
    }

    @Test void convertFromUrlImportsIt() throws IOException {
        RunConvert rc = client(start((c, n) -> Reply.ok("{\"id\":\"job_2\",\"status\":\"" + (c.method().equals("GET") ? "finished" : "waiting") + "\"}")));
        rc.convert(URI.create("https://example.com/a/talk.MP4?x=1"), RunConvert.ConvertOptions.to("mp3"));
        JsonNode tasks = RunConvert.JSON.readTree(calls.get(0).body()).path("tasks");
        assertEquals("import/url", tasks.path("import-1").path("operation").asText());
        assertEquals("https://example.com/a/talk.MP4?x=1", tasks.path("import-1").path("source_url").asText());
        assertEquals("mp4", tasks.path("convert-1").path("input_format").asText());
    }
}
