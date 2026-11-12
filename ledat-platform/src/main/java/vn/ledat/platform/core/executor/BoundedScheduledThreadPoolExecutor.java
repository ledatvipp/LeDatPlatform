package vn.ledat.platform.core.executor;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Scheduled executor with a hard bound on outstanding delayed, periodic, and running tasks. */
final class BoundedScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
    private final int capacity;
    private final AtomicInteger outstanding = new AtomicInteger();

    BoundedScheduledThreadPoolExecutor(int corePoolSize, int capacity, ThreadFactory threadFactory,
                                       RejectedExecutionHandler rejectionHandler) {
        super(corePoolSize, threadFactory, rejectionHandler);
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(unit, "unit");
        Permit permit = reserve(command);
        try {
            ScheduledFuture<?> delegate = super.schedule(() -> {
                try {
                    command.run();
                } finally {
                    permit.release();
                }
            }, delay, unit);
            return new ReleasingScheduledFuture<>(delegate, permit);
        } catch (RuntimeException | Error error) {
            permit.release();
            throw error;
        }
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        Objects.requireNonNull(callable, "callable");
        Objects.requireNonNull(unit, "unit");
        Permit permit = reserve(callable);
        try {
            ScheduledFuture<V> delegate = super.schedule(() -> {
                try {
                    return callable.call();
                } finally {
                    permit.release();
                }
            }, delay, unit);
            return new ReleasingScheduledFuture<>(delegate, permit);
        } catch (RuntimeException | Error error) {
            permit.release();
            throw error;
        }
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return schedulePeriodic(command, initialDelay, period, unit, true);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return schedulePeriodic(command, initialDelay, delay, unit, false);
    }

    private ScheduledFuture<?> schedulePeriodic(Runnable command, long initialDelay, long interval,
                                                TimeUnit unit, boolean fixedRate) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(unit, "unit");
        Permit permit = reserve(command);
        Runnable guarded = () -> {
            try {
                command.run();
            } catch (Throwable error) {
                permit.release();
                throw error;
            }
        };
        try {
            ScheduledFuture<?> delegate = fixedRate
                    ? super.scheduleAtFixedRate(guarded, initialDelay, interval, unit)
                    : super.scheduleWithFixedDelay(guarded, initialDelay, interval, unit);
            return new ReleasingScheduledFuture<>(delegate, permit);
        } catch (RuntimeException | Error error) {
            permit.release();
            throw error;
        }
    }

    private Permit reserve(Object task) {
        while (true) {
            int current = outstanding.get();
            if (current >= capacity) {
                getRejectedExecutionHandler().rejectedExecution(task instanceof Runnable runnable ? runnable : () -> { }, this);
                throw new RejectedExecutionException("Managed scheduled executor capacity reached: " + capacity);
            }
            if (outstanding.compareAndSet(current, current + 1)) {
                return new Permit();
            }
        }
    }

    private final class Permit {
        private final AtomicBoolean released = new AtomicBoolean();

        private void release() {
            if (released.compareAndSet(false, true)) {
                outstanding.decrementAndGet();
            }
        }
    }

    private static final class ReleasingScheduledFuture<V> implements ScheduledFuture<V> {
        private final ScheduledFuture<V> delegate;
        private final BoundedScheduledThreadPoolExecutor.Permit permit;

        private ReleasingScheduledFuture(ScheduledFuture<V> delegate, BoundedScheduledThreadPoolExecutor.Permit permit) {
            this.delegate = delegate;
            this.permit = permit;
        }

        @Override public long getDelay(TimeUnit unit) { return delegate.getDelay(unit); }
        @Override public int compareTo(java.util.concurrent.Delayed other) { return delegate.compareTo(other); }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = delegate.cancel(mayInterruptIfRunning);
            if (cancelled || delegate.isDone()) {
                permit.release();
            }
            return cancelled;
        }

        @Override public boolean isCancelled() { return delegate.isCancelled(); }
        @Override public boolean isDone() { return delegate.isDone(); }
        @Override public V get() throws java.lang.InterruptedException, java.util.concurrent.ExecutionException { return delegate.get(); }
        @Override public V get(long timeout, TimeUnit unit) throws java.lang.InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException { return delegate.get(timeout, unit); }
    }
}
