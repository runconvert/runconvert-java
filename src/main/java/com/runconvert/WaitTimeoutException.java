package com.runconvert;

/** {@link Jobs#waitFor} gave up before the job reached "finished" or "error". */
public class WaitTimeoutException extends RunConvertException {
    private final Job job;

    public WaitTimeoutException(Job job, java.time.Duration timeout) {
        super("Job " + job.id + " still " + job.status + " after " + timeout.getSeconds() + "s", 0, "WAIT_TIMEOUT", null);
        this.job = job;
    }

    public Job getJob() { return job; }
}
