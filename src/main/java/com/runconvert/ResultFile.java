package com.runconvert;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A result file: name, temporary download link and size. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResultFile {
    public String filename;
    /** Temporary download link. */
    public String url;
    public Long size;
    public String created_at;
}
