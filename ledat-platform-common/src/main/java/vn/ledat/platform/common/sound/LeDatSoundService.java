package vn.ledat.platform.common.sound;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared sound cue cache for LeDat plugins.
 *
 * <p>Config convention:</p>
 * <pre>
 * sounds:
 *   enabled: true
 *   profile: PREMIUM
 *   cues:
 *     success:
 *       enabled: true
 *       sound: ENTITY_PLAYER_LEVELUP
 *       volume: 0.7
 *       pitch: 1.2
 *       cooldown-ms: 80
 * </pre>
 */
public final class LeDatSoundService {
    private final JavaPlugin plugin;
    private final Map<String, SoundCue> cues = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPlayed = new ConcurrentHashMap<>();
    private final Set<String> warnedInvalid = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;
    private volatile boolean logInvalid = true;
    private volatile String profile = "PREMIUM";

    public LeDatSoundService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        reload();
    }

    public void reload() {
        cues.clear();
        lastPlayed.clear();
        warnedInvalid.clear();
        this.enabled = plugin.getConfig().getBoolean("sounds.enabled", true);
        this.logInvalid = plugin.getConfig().getBoolean("sounds.log-invalid", true);
        this.profile = plugin.getConfig().getString("sounds.profile", "PREMIUM");
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("sounds.cues");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection cueSection = section.getConfigurationSection(key);
            if (cueSection == null) {
                continue;
            }
            SoundCue cue = parseCue("sounds.cues." + key, cueSection);
            if (cue != null) {
                cues.put(normalize(key), cue);
            }
        }
    }

    public String profile() {
        return profile;
    }

    public boolean hasCue(String cue) {
        return cues.containsKey(normalize(cue));
    }

    public void play(Player player, String cue) {
        if (!enabled || player == null || !player.isOnline()) {
            return;
        }
        SoundCue soundCue = cues.get(normalize(cue));
        if (soundCue == null || !soundCue.enabled()) {
            return;
        }
        String cooldownKey = player.getUniqueId() + ":" + normalize(cue);
        long now = System.currentTimeMillis();
        if (soundCue.cooldownMillis() > 0L) {
            Long last = lastPlayed.get(cooldownKey);
            if (last != null && now - last < soundCue.cooldownMillis()) {
                return;
            }
            lastPlayed.put(cooldownKey, now);
        }
        player.playSound(player.getLocation(), soundCue.sound(), soundCue.volume(), soundCue.pitch());
    }

    public void playCountdown(Player player, String cue, int secondsLeft) {
        if (secondsLeft <= 0) {
            return;
        }
        SoundCue soundCue = cues.get(normalize(cue));
        if (soundCue == null || !soundCue.enabled()) {
            return;
        }
        if (soundCue.onlyLastSeconds() > 0 && secondsLeft > soundCue.onlyLastSeconds()) {
            return;
        }
        int interval = Math.max(1, soundCue.intervalSeconds());
        if (secondsLeft % interval != 0) {
            return;
        }
        play(player, cue);
    }

    public void cleanup(long maxAgeMillis) {
        long cutoff = System.currentTimeMillis() - Math.max(1_000L, maxAgeMillis);
        lastPlayed.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < cutoff);
    }

    public void clearRuntimeCache() {
        lastPlayed.clear();
    }

    private SoundCue parseCue(String path, ConfigurationSection section) {
        if (!section.getBoolean("enabled", true)) {
            return new SoundCue(false, Sound.UI_BUTTON_CLICK, 1.0f, 1.0f, 0L, 0, 1);
        }
        String rawSound = section.getString("sound", "UI_BUTTON_CLICK");
        Sound sound;
        try {
            sound = Sound.valueOf(rawSound.trim().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        } catch (Throwable error) {
            if (logInvalid && warnedInvalid.add(path)) {
                plugin.getLogger().warning("[Sound] Invalid sound at " + path + ".sound: '" + rawSound + "'; cue disabled.");
            }
            return null;
        }
        float volume = clamp((float) section.getDouble("volume", 1.0D), 0.0f, 10.0f);
        float pitch = clamp((float) section.getDouble("pitch", 1.0D), 0.0f, 2.0f);
        long cooldownMillis = Math.max(0L, section.getLong("cooldown-ms", section.getLong("cooldownMillis", 0L)));
        int onlyLastSeconds = Math.max(0, section.getInt("only-last-seconds", section.getInt("onlyLastSeconds", 0)));
        int intervalSeconds = Math.max(1, section.getInt("interval-seconds", section.getInt("intervalSeconds", 1)));
        return new SoundCue(true, sound, volume, pitch, cooldownMillis, onlyLastSeconds, intervalSeconds);
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String normalize(String cue) {
        return Objects.toString(cue, "").trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public record SoundCue(boolean enabled, Sound sound, float volume, float pitch, long cooldownMillis, int onlyLastSeconds, int intervalSeconds) {
    }
}
