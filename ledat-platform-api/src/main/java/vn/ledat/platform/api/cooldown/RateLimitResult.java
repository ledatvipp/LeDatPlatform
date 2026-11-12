package vn.ledat.platform.api.cooldown;

import java.time.Duration;

public record RateLimitResult(boolean allowed, int remaining, Duration retryAfter) {
    public static RateLimitResult allowed(int remaining) {
        return new RateLimitResult(true, remaining, Duration.ZERO);
    }

    public static RateLimitResult denied(Duration retryAfter) {
        return new RateLimitResult(false, 0, retryAfter);
    }
}
