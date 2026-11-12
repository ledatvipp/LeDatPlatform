package vn.ledat.platform.api.scheduler;

import java.util.Objects;

public record EntityTaskOutcome<T>(Status status, T value, Throwable error) {
    public EntityTaskOutcome {
        status = Objects.requireNonNull(status, "status");
        if (status == Status.FAILED && error == null) {
            throw new IllegalArgumentException("FAILED outcome requires an error");
        }
        if (status != Status.FAILED) {
            error = null;
        }
        if (status != Status.EXECUTED) {
            value = null;
        }
    }

    public static <T> EntityTaskOutcome<T> executed(T value) {
        return new EntityTaskOutcome<>(Status.EXECUTED, value, null);
    }

    public static <T> EntityTaskOutcome<T> retired() {
        return new EntityTaskOutcome<>(Status.RETIRED, null, null);
    }

    public static <T> EntityTaskOutcome<T> rejected() {
        return new EntityTaskOutcome<>(Status.REJECTED, null, null);
    }

    public static <T> EntityTaskOutcome<T> failed(Throwable error) {
        return new EntityTaskOutcome<>(Status.FAILED, null, Objects.requireNonNull(error, "error"));
    }

    public boolean executed() {
        return status == Status.EXECUTED;
    }

    public enum Status {
        EXECUTED,
        RETIRED,
        REJECTED,
        FAILED
    }
}
