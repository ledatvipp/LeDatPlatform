package vn.ledat.platform.api.sound;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

public interface SoundService {
    default boolean play(Plugin owner, Player player, Sound sound, float volume, float pitch) {
        return play(owner, player, sound, SoundCategory.MASTER, volume, pitch);
    }

    default boolean play(Plugin owner, Player player, Sound sound, SoundCategory category, float volume, float pitch) {
        return false;
    }

    default boolean playAt(Plugin owner, Player player, Location location, Sound sound, float volume, float pitch) {
        return playAt(owner, player, location, sound, SoundCategory.MASTER, volume, pitch);
    }

    default boolean playAt(Plugin owner, Player player, Location location, Sound sound, SoundCategory category, float volume, float pitch) {
        return false;
    }

    default boolean playWorld(Plugin owner, Location location, Sound sound, float volume, float pitch) {
        return playWorld(owner, location, sound, SoundCategory.MASTER, volume, pitch);
    }

    default boolean playWorld(Plugin owner, Location location, Sound sound, SoundCategory category, float volume, float pitch) {
        return false;
    }

    default boolean playCue(Plugin owner, Player player, String cueKey) {
        return playCue(owner, player, cueKey, null);
    }

    default boolean playCue(Plugin owner, Player player, String cueKey, SoundCue fallback) {
        return false;
    }

    default boolean playCountdownCue(Plugin owner, Player player, String cueKey, int secondsLeft) {
        return playCountdownCue(owner, player, cueKey, secondsLeft, null);
    }

    default boolean playCountdownCue(Plugin owner, Player player, String cueKey, int secondsLeft, SoundCue fallback) {
        return false;
    }

    default SoundCue cue(Plugin owner, ConfigurationSection section, String path, SoundCue fallback) {
        return fallback == null ? SoundCue.disabled() : fallback;
    }

    default SoundCue cue(Plugin owner, String path, String rawSound, SoundCue fallback) {
        return fallback == null ? SoundCue.disabled() : fallback;
    }

    default void reload() {
    }

    default void reload(Plugin owner) {
    }

    default void cleanup(Plugin owner) {
    }

    default SoundStats stats() {
        return new SoundStats(0, 0, 0L, 0L, 0L, 0L, 0L);
    }

    static SoundService disabled() {
        return Disabled.INSTANCE;
    }

    record SoundStats(
            int ownerCaches,
            int cooldownEntries,
            long directPlays,
            long cuePlays,
            long blockedCooldowns,
            long missingCues,
            long invalidSounds
    ) {
    }

    final class Disabled implements SoundService {
        private static final SoundService INSTANCE = new Disabled();

        private Disabled() {
        }
    }
}
