# RunConvert SDK for Java

Convert files between 240+ formats (3,000+ conversions) with the [RunConvert API](https://www.runconvert.com/docs):
documents, images, video, audio, ebooks, archives, fonts and CAD. Java 11+, one dependency (Jackson).

```xml
<dependency>
  <groupId>com.runconvert</groupId>
  <artifactId>runconvert-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
import com.runconvert.RunConvert;
import java.nio.file.Path;

RunConvert rc = RunConvert.builder().build(); // reads RUNCONVERT_API_KEY

// One call: upload, convert, wait, download.
RunConvert.ConvertResult r = rc.convert(Path.of("report.docx"), RunConvert.ConvertOptions.to("pdf").outDir(Path.of("out")));
System.out.println(r.saved); // [out/report.pdf]
```

Get an API key in **Dashboard → API Keys** on [runconvert.com](https://www.runconvert.com). Keep keys on the server.

## Convert

```java
// From a URL (imported by RunConvert, nothing to upload)
rc.convert(URI.create("https://example.com/talk.mp4"), RunConvert.ConvertOptions.to("mp3").options(Map.of("audioBitrate", "192k")));

// Progress and a time limit
rc.convert(Path.of("movie.mov"), RunConvert.ConvertOptions.to("mp4").outDir(Path.of("out"))
        .timeout(Duration.ofMinutes(10)).onUpdate(job -> System.out.println(job.status)));
```

## Jobs with your own tasks

```java
import com.runconvert.Tasks;

// Merge two PDFs
Map<String, Object> tasks = new LinkedHashMap<>();
tasks.put("a", Tasks.importUrl("https://example.com/cover.pdf"));
tasks.put("b", Tasks.importUrl("https://example.com/body.pdf"));
tasks.put("merge", Tasks.convert("pdf", "pdf", List.of("a", "b"), null));
tasks.put("export", Tasks.exportUrl("merge"));
Job job = rc.jobs().waitFor(rc.jobs().create(tasks, "merge", null).id);
for (ResultFile f : job.exportedFiles()) rc.files().download(f, Path.of("out"));

// Upload your own file
Map<String, Object> up = new LinkedHashMap<>();
up.put("upload", Tasks.importUpload());
up.put("convert", Tasks.convert("png", "webp", "upload", Map.of("image_quality", 80)));
up.put("export", Tasks.exportUrl("convert"));
Job created = rc.jobs().create(up);
rc.files().upload(created.task("upload").upload_url, Path.of("image.png"), null);
```

| Method | What it does |
|---|---|
| `rc.jobs().create / get / list / all / waitFor / cancel / delete` | Jobs, with paging (`list`, `all`) and polling (`waitFor`) |
| `rc.files().upload(url, path, name)` / `download(file, dir)` | Transfers |
| `rc.operations().list(category)` / `forInput("mp4")` / `forPair("mp4", "mp3")` | Formats, targets, engines as `JsonNode` (no key needed) |
| `rc.me()` | Account, team and credit balance |

## Webhooks

```java
// Spring: @PostMapping("/webhooks/runconvert") with @RequestBody byte[] body
JsonNode event = Webhooks.constructEvent(body, request.getHeader("X-RunConvert-Signature"), System.getenv("RUNCONVERT_WEBHOOK_SECRET"));
if ("job.finished".equals(event.path("event").asText())) { /* … */ }
```

`Webhooks.verify(body, signature, secret)` returns `true` or `false`.

## Errors

| Exception | When |
|---|---|
| `RunConvertException` | The API said no: `getStatus()`, `getCode()` (e.g. `BILLING_INSUFFICIENT_CREDITS`), `getMessage()` |
| `JobFailedException` | The job ended in `error`; `getTask()` has `code`, `reason`, `message` |
| `WaitTimeoutException` | `waitFor` ran past its timeout |

All are unchecked. Network errors, `429` and `5xx` are retried twice with backoff (`maxRetries`). Creating a job
is only retried when the request never reached the API, so a job is never created twice.

## Development

```bash
mvn test                                      # unit tests, no network
RUNCONVERT_API_KEY=rc_live_… mvn test -Plive  # real API, about 2 credits
```

## License

MIT
