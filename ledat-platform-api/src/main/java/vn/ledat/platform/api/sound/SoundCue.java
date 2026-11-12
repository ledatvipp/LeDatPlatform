package vn.ledat.platform.api.sound;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;

public record SoundCue(
        boolean enabled,
        Sound sound,
        SoundCategory category,
        float volume,
        float pitch,
        long cooldownMillis,
        int onlyLastSeconds,
        int intervalSeconds
) {
    public SoundCue {
        category = category == null ? SoundCategory.MASTER : category;
        volume = (float) clamp(volume, 0.0D, 4.0D);
        pitch = (float) clamp(pitch, 0.5D, 2.0D);
        cooldownMillis = Math.max(0L, cooldownMillis);
        onlyLastSeconds = Math.max(0, onlyLastSeconds);
        intervalSeconds = Math.max(1, intervalSeconds);
    }

    public static SoundCue of(Sound sound, float volume, float pitch) {
        return new SoundCue(true, sound, SoundCategory.MASTER, volume, pitch, 0L, 0, 1);
    }

    public static SoundCue of(Sound sound, SoundCategory category, float volume, float pitch) {
        return new SoundCue(true, sound, category, volume, pitch, 0L, 0, 1);
    }

    public static SoundCue disabled() {
        return new SoundCue(false, null, SoundCategory.MASTER, 1.0F, 1.0F, 0L, 0, 1);
    }

    public SoundCue withCooldownMillis(long cooldownMillis) {
        return new SoundCue(enabled, sound, category, volume, pitch, cooldownMillis, onlyLastSeconds, intervalSeconds);
    }

    public SoundCue withCountdown(int onlyLastSeconds, int intervalSeconds) {
        return new SoundCue(enabled, sound, category, volume, pitch, cooldownMillis, onlyLastSeconds, intervalSeconds);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
