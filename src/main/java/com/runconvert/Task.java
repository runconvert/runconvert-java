package com.runconvert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** A step of a job. On error, {@code code} is the category and {@code reason} the exact, stable cause. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Task {
    public String id;
    public String job_id;
    public String name;
    public String operation;
    public String status;
    public Integer percent;
    public String message;
    public String engine;
    public String engine_version;
    public Integer credits;
    public List<String> input;
    /** import/direct/upload only: PUT the file here (see {@link Files#upload}). */
    public String upload_url;
    public String upload_deadline;
    public Result result;
    public String code;
    public String reason;
    public Map<String, Object> reason_params;
    public String created_at;
    public String started_at;
    public String ended_at;
    public String expires_at;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        public List<ResultFile> files;
    }
}
