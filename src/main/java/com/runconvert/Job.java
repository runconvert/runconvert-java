package com.runconvert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A job: named tasks that run in order (import → convert → export). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Job {
    public String id;
    /** waiting | processing | finished | error */
    public String status;
    public String tag;
    public Map<String, Object> metadata;
    public Integer credits_used;
    public String credit_type;
    public String created_at;
    public String started_at;
    public String ended_at;
    public String expires_at;
    public String updated_at;
    public List<Task> tasks;

    /** The task with this name, or null. */
    public Task task(String name) {
        if (tasks != null) for (Task t : tasks) if (name.equals(t.name)) return t;
        return null;
    }

    /** The files produced by the job's export/url tasks. */
    public List<ResultFile> exportedFiles() {
        List<ResultFile> files = new ArrayList<>();
        if (tasks != null) for (Task t : tasks)
            if ("export/url".equals(t.operation) && t.result != null && t.result.files != null) files.addAll(t.result.files);
        return files;
    }
}
