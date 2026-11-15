package com.runconvert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/** Create, read, wait for and stop jobs. */
public final class Jobs {
    private final RunConvert c;

    Jobs(RunConvert c) { this.c = c; }

    private static String esc(String id) { return URLEncoder.encode(id, StandardCharsets.UTF_8); }

    /** Creates a job from named tasks (see {@link Tasks}). Returned with its tasks. */
    public Job create(Map<String, ?> tasks, String tag, Map<String, ?> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tasks", tasks);
        if (tag != null) body.put("tag", tag);
        if (metadata != null) body.put("metadata", metadata);
        return c.convertValue(c.request("POST", "/jobs", body, Map.of("include", "tasks")).data, Job.class);
    }

    public Job create(Map<String, ?> tasks) { return create(tasks, null, null); }

    /** A job with its tasks. */
    public Job get(String id) {
        return c.convertValue(c.request("GET", "/jobs/" + esc(id), null, Map.of("include", "tasks")).data, Job.class);
    }

    /** One page of jobs, newest first. */
    public static final class Page {
        public final List<Job> data;
        public final int page;
        public final int totalPages;
        public final int total;

        Page(List<Job> data, JsonNode meta) {
            this.data = data;
            this.page = meta == null ? 0 : meta.path("page").asInt();
            this.totalPages = meta == null ? 0 : meta.path("total_pages").asInt();
            this.total = meta == null ? 0 : meta.path("total").asInt();
        }
    }

    /** One page of jobs; status/tag may be null, page and perPage 0 for the defaults. */
    public Page list(String status, String tag, int page, int perPage) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("filter[status]", status);
        q.put("filter[tag]", tag);
        if (page > 0) q.put("page", page);
        if (perPage > 0) q.put("per_page", perPage);
        RunConvert.Result r = c.request("GET", "/jobs", null, q);
        List<Job> jobs = r.data == null ? new ArrayList<>() : c.convertValue(r.data, new TypeReference<List<Job>>() {});
        return new Page(jobs, r.meta);
    }

    /** Every job, page by page: {@code for (Job j : rc.jobs().all("finished", null, 100))}. */
    public Iterable<Job> all(String status, String tag, int perPage) {
        return () -> new Iterator<>() {
            private int page = 0;
            private Iterator<Job> current = java.util.Collections.emptyIterator();
            private boolean last = false;

            @Override public boolean hasNext() {
                while (!current.hasNext() && !last) {
                    Page p = list(status, tag, ++page, perPage);
                    current = p.data.iterator();
                    last = p.data.isEmpty() || (p.totalPages > 0 && page >= p.totalPages);
                }
                return current.hasNext();
            }

            @Override public Job next() {
                if (!hasNext()) throw new NoSuchElementException();
                return current.next();
            }
        };
    }

    /** Cancels pending tasks; credits for unfinished tasks are refunded. */
    public Job cancel(String id) {
        return c.convertValue(c.request("POST", "/jobs/" + esc(id) + "/cancel", null, null).data, Job.class);
    }

    /** Deletes a job. */
    public void delete(String id) {
        c.request("DELETE", "/jobs/" + esc(id), null, null);
    }

    /**
     * Polls until "finished" or "error" (0.7 s, slowing to 3 s). Throws {@link JobFailedException}
     * for a failed job and {@link WaitTimeoutException} after {@code timeout} (null: no limit).
     */
    public Job waitFor(String id, Duration timeout, Consumer<Job> onUpdate) {
        long started = System.nanoTime();
        long delay = 700;
        while (true) {
            Job job = get(id);
            if (onUpdate != null) onUpdate.accept(job);
            if ("finished".equals(job.status)) return job;
            if ("error".equals(job.status)) {
                Task failed = null;
                if (job.tasks != null) for (Task t : job.tasks) if ("error".equals(t.status)) { failed = t; break; }
                throw new JobFailedException(job, failed);
            }
            if (timeout != null && Duration.ofNanos(System.nanoTime() - started).plusMillis(delay).compareTo(timeout) > 0)
                throw new WaitTimeoutException(job, timeout);
            c.sleep(Duration.ofMillis(delay));
            delay = Math.min(delay + 300, 3000);
        }
    }

    public Job waitFor(String id) { return waitFor(id, null, null); }
}
