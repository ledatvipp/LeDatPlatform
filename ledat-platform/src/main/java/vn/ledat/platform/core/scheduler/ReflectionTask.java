package vn.ledat.platform.core.scheduler;

import vn.ledat.platform.api.scheduler.LeDatTask;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class ReflectionTask implements LeDatTask {
    private final Object handle;
    private final Runnable fallbackCancel;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    ReflectionTask(Object handle, Runnable fallbackCancel) {
        this.handle = handle;
        this.fallbackCancel = fallbackCancel;
    }

    static LeDatTask of(Object handle) {
        return new ReflectionTask(handle, null);
    }

    static LeDatTask of(Runnable cancel) {
        return new ReflectionTask(null, Objects.requireNonNull(cancel, "cancel"));
    }

    @Override
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        if (fallbackCancel != null) {
            fallbackCancel.run();
            return;
        }
        if (handle == null) {
            return;
        }
        try {
            Method cancel = handle.getClass().getMethod("cancel");
            cancel.invoke(handle);
        } catch (Throwable ignored) {
        }
    }
}
