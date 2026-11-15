package com.runconvert;

/** The job ended with status "error". {@link #getTask()} is the first failed task (code, reason, message). */
public class JobFailedException extends RunConvertException {
    private final Job job;
    private final Task task;

    public JobFailedException(Job job, Task task) {
        super(task != null && task.message != null && !task.message.isEmpty() ? task.message : "Job " + job.id + " failed",
                0, task != null ? task.code : "", null);
        this.job = job;
        this.task = task;
    }

    public Job getJob() { return job; }

    public Task getTask() { return task; }
}
