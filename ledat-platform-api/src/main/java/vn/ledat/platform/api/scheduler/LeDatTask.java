package vn.ledat.platform.api.scheduler;

public interface LeDatTask {
    LeDatTask NOOP = () -> { };

    void cancel();

    default void cancelQuietly() {
        try {
            cancel();
        } catch (Throwable ignored) {
        }
    }
}
