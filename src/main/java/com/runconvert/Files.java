package com.runconvert;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Uploads to import/direct/upload tasks and downloads of results. */
public final class Files {
    private final RunConvert c;

    Files(RunConvert c) { this.c = c; }

    /** PUTs a local file to a task's upload_url before its deadline; filename (may be null) keeps its name. */
    public void upload(String uploadUrl, Path file, String filename) {
        if (uploadUrl == null || uploadUrl.isEmpty()) throw new IllegalArgumentException("Empty upload URL (is it an import/direct/upload task?)");
        String name = filename != null ? filename : file.getFileName().toString();
        String url = uploadUrl + (uploadUrl.contains("?") ? "&" : "?") + "filename=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/octet-stream").header("User-Agent", c.userAgent)
                    .PUT(HttpRequest.BodyPublishers.ofFile(file)).build();
            HttpResponse<String> res = c.http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                String body = res.body();
                throw new RunConvertException("Upload failed (HTTP " + res.statusCode() + "): " + body.substring(0, Math.min(300, body.length())), res.statusCode(), "", body);
            }
        } catch (IOException e) {
            throw new RunConvertException("Upload failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunConvertException("Interrupted", e);
        }
    }

    /** Saves a result file into dir (created if needed), keeping its name. Returns the path. */
    public Path download(ResultFile file, Path dir) {
        return download(file.url, dir, file.filename);
    }

    /** Saves a URL into dir; filename null uses the last part of the URL. */
    public Path download(String url, Path dir, String filename) {
        String name = filename != null ? filename : URI.create(url).getPath().replaceAll(".*/", "");
        try {
            java.nio.file.Files.createDirectories(dir);
            Path dest = dir.resolve(Path.of(name).getFileName());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", c.userAgent).GET().build();
            HttpResponse<Path> res = c.http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
            if (res.statusCode() >= 300) {
                java.nio.file.Files.deleteIfExists(dest);
                throw new RunConvertException("Download failed (HTTP " + res.statusCode() + ")", res.statusCode(), "", null);
            }
            return dest;
        } catch (IOException e) {
            throw new RunConvertException("Download failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunConvertException("Interrupted", e);
        }
    }
}
