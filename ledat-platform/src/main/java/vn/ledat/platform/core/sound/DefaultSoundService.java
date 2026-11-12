package vn.ledat.platform.core.sound;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.sound.SoundCue;
import vn.ledat.platform.api.sound.SoundService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultSoundService implements SoundService {
    private static final Set<String> RESERVED_SOUND_KEYS = Set.of("enabled", "log-invalid", "profile", "cues");
    private static final java.util.regex.Pattern OWNER_KEY_SANITIZE = java.util.regex.Pattern.compile("[^a-z0-9._-]+");

    private final Plugin platformPlugin;
    private final LeDatScheduler scheduler;
    private final Map<String, String> ownerKeyCache = new ConcurrentHashMap<>();
    private final Map<String, OwnerSounds> owners = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Set<String> warnedInvalid = ConcurrentHashMap.newKeySet();
    private final AtomicLong directPlays = new AtomicLong();
    private final AtomicLong cuePlays = new AtomicLong();
    private final AtomicLong blockedCooldowns = new AtomicLong();
    private final AtomicLong missingCues = new AtomicLong();
    private final AtomicLong invalidSounds = new AtomicLong();

    public DefaultSoundService(Plugin platformPlugin, LeDatScheduler scheduler) {
        this.platformPlugin = platformPlugin;
        this.scheduler = scheduler;
    }

    @Override
    public boolean play(Plugin owner, Player player, Sound sound, SoundCategory category, float volume, float pitch) {
        if (player == null || !player.isOnline() || sound == null) {
            return false;
        }
        schedulePlay(owner, player, null, sound, category, volume, pitch);
        directPlays.incrementAndGet();
        return true;
    }

    @Override
    public boolean playAt(Plugin owner, Player player, Location location, Sound sound, SoundCategory category, float volume, float pitch) {
        if (player == null || !player.isOnline() || sound == null) {
            return false;
        }
        schedulePlay(owner, player, location, sound, category, volume, pitch);
        directPlays.incrementAndGet();
        return true;
    }

    @Override
    public boolean playWorld(Plugin owner, Location location, Sound sound, SoundCategory category, float volume, float pitch) {
        if (location == null || location.getWorld() == null || sound == null) {
            return false;
        }
        scheduleWorldPlay(owner, location, sound, category, volume, pitch);
        directPlays.incrementAndGet();
        return true;
    }

    @Override
    public boolean playCue(Plugin owner, Player player, String cueKey, SoundCue fallback) {
        return playResolvedCue(owner, player, cueKey, -1, fallback);
    }

    @Override
    public boolean playCountdownCue(Plugin owner, Player player, String cueKey, int secondsLeft, SoundCue fallback) {
        if (secondsLeft <= 0) {
            return false;
        }
        return playResolvedCue(owner, player, cueKey, secondsLeft, fallback);
    }

    @Override
    public SoundCue cue(Plugin owner, ConfigurationSection section, String path, SoundCue fallback) {
        if (section == null) {
            return fallback == null ? SoundCue.disabled() : fallback;
        }
        boolean logInvalid = true;
        if (owner instanceof JavaPlugin javaPlugin) {
            logInvalid = javaPlugin.getConfig().getBoolean("sounds.log-invalid", true);
        }
        boolean enabled = section.getBoolean("enabled", fallback == null || fallback.enabled());
        String safePath = path == null || path.isBlank() ? section.getCurrentPath() : path;
        Sound fallbackSound = fallback == null ? null : fallback.sound();
        String rawSound = section.getString("sound", fallbackSound == null ? null : fallbackSound.toString());
        Sound sound = parseSound(owner, safePath + ".sound", rawSound, logInvalid);
        if (sound == null && fallbackSound != null && rawSound != null && !rawSound.trim().equalsIgnoreCase("none")) {
            sound = fallbackSound;
        }
        if (rawSound != null && rawSound.trim().equalsIgnoreCase("none")) {
            enabled = false;
        }
        SoundCategory fallbackCategory = fallback == null ? SoundCategory.MASTER : fallback.category();
        SoundCategory category = parseCategory(owner, safePath + ".category",
                section.getString("category", fallbackCategory.name()), logInvalid);
        float volume = (float) clamp(section.getDouble("volume", fallback == null ? 1.0D : fallback.volume()), 0.0D, 4.0D);
        float pitch = (float) clamp(section.getDouble("pitch", fallback == null ? 1.0D : fallback.pitch()), 0.5D, 2.0D);
        long cooldownMillis = Math.max(0L, section.getLong("cooldown-ms", fallback == null ? 0L : fallback.cooldownMillis()));
        int onlyLastSeconds = Math.max(0, section.getInt("only-last-seconds", fallback == null ? 0 : fallback.onlyLastSeconds()));
        int intervalSeconds = Math.max(1, section.getInt("interval-seconds", fallback == null ? 1 : fallback.intervalSeconds()));
        return new SoundCue(enabled, sound, category, volume, pitch, cooldownMillis, onlyLastSeconds, intervalSeconds);
    }

    @Override
    public SoundCue cue(Plugin owner, String path, String rawSound, SoundCue fallback) {
        if (rawSound == null || rawSound.isBlank() || rawSound.trim().equalsIgnoreCase("none")) {
            return SoundCue.disabled();
        }
        SoundCategory fallbackCategory = fallback == null ? SoundCategory.MASTER : fallback.category();
        float volume = fallback == null ? 1.0F : fallback.volume();
        float pitch = fallback == null ? 1.0F : fallback.pitch();
        long cooldownMillis = fallback == null ? 0L : fallback.cooldownMillis();
        int onlyLastSeconds = fallback == null ? 0 : fallback.onlyLastSeconds();
        int intervalSeconds = fallback == null ? 1 : fallback.intervalSeconds();
        Sound sound = parseRawSound(rawSound);
        if (sound == null && fallback != null) {
            sound = fallback.sound();
        }
        if (sound == null) {
            return SoundCue.disabled();
        }
        return new SoundCue(true, sound, fallbackCategory, volume, pitch, cooldownMillis, onlyLastSeconds, intervalSeconds);
    }

    @Override
    public void reload() {
        owners.clear();
        cooldowns.clear();
        warnedInvalid.clear();
    }

    @Override
    public void reload(Plugin owner) {
        if (owner == null) {
            reload();
            return;
        }
        String ownerKey = ownerKey(owner);
        owners.remove(ownerKey);
        cooldowns.keySet().removeIf(key -> key.startsWith(ownerKey + "|"));
        warnedInvalid.removeIf(key -> key.startsWith(ownerKey + "|"));
    }

    @Override
    public void cleanup(Plugin owner) {
        reload(owner);
    }

    private final AtomicLong expiredCooldownCleanups = new AtomicLong();

    public int cleanupExpiredCooldowns() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Long> entry : cooldowns.entrySet()) {
            Long lastPlayed = entry.getValue();
            if (lastPlayed != null && now - lastPlayed > 600_000L) {
                if (cooldowns.remove(entry.getKey(), lastPlayed)) {
                    removed++;
                    expiredCooldownCleanups.incrementAndGet();
                }
            }
        }
        return removed;
    }

    @Override
    public SoundStats stats() {
        return new SoundStats(
                owners.size(),
                cooldowns.size(),
                directPlays.get(),
                cuePlays.get(),
                blockedCooldowns.get(),
                missingCues.get(),
                invalidSounds.get()
        );
    }

    private boolean playResolvedCue(Plugin owner, Player player, String rawCueKey, int secondsLeft, SoundCue fallback) {
        if (owner == null || player == null || !player.isOnline()) {
            return false;
        }
        String cueKey = normalizeCue(rawCueKey);
        if (cueKey.isBlank()) {
            return false;
        }

        OwnerSounds ownerSounds = ownerSounds(owner);
        if (!ownerSounds.enabled()) {
            return false;
        }

        SoundCue cue = ownerSounds.cues().get(cueKey);
        if (cue == null) {
            cue = fallback;
        } else if (cue.enabled() && cue.sound() == null && fallback != null && fallback.sound() != null) {
            cue = new SoundCue(true, fallback.sound(), cue.category(), cue.volume(), cue.pitch(),
                    cue.cooldownMillis(), cue.onlyLastSeconds(), cue.intervalSeconds());
        }
        if (cue == null) {
            missingCues.incrementAndGet();
            return false;
        }
        if (!cue.enabled() || cue.sound() == null) {
            return false;
        }
        if (secondsLeft > 0) {
            if (cue.onlyLastSeconds() > 0 && secondsLeft > cue.onlyLastSeconds()) {
                return false;
            }
            if (secondsLeft % Math.max(1, cue.intervalSeconds()) != 0) {
                return false;
            }
        }
        if (cooldownBlocked(owner, player.getUniqueId(), cueKey, cue.cooldownMillis())) {
            blockedCooldowns.incrementAndGet();
            return false;
        }

        schedulePlay(owner, player, null, cue.sound(), cue.category(), cue.volume(), cue.pitch());
        cuePlays.incrementAndGet();
        return true;
    }

    private void schedulePlay(Plugin owner, Player player, Location location, Sound sound, SoundCategory category, float volume, float pitch) {
        Plugin taskOwner = owner == null ? platformPlugin : owner;
        SoundCategory safeCategory = category == null ? SoundCategory.MASTER : category;
        float safeVolume = (float) clamp(volume, 0.0D, 4.0D);
        float safePitch = (float) clamp(pitch, 0.5D, 2.0D);
        Location safeLocation = location == null ? null : location.clone();
        scheduler.entity(taskOwner, player, () -> {
            if (player.isOnline()) {
                player.playSound(safeLocation == null ? player.getLocation() : safeLocation, sound, safeCategory, safeVolume, safePitch);
            }
        });
    }

    private void scheduleWorldPlay(Plugin owner, Location location, Sound sound, SoundCategory category, float volume, float pitch) {
        Plugin taskOwner = owner == null ? platformPlugin : owner;
        Location safeLocation = location.clone();
        SoundCategory safeCategory = category == null ? SoundCategory.MASTER : category;
        float safeVolume = (float) clamp(volume, 0.0D, 4.0D);
        float safePitch = (float) clamp(pitch, 0.5D, 2.0D);
        scheduler.region(taskOwner, safeLocation, () -> {
            if (safeLocation.getWorld() != null) {
                safeLocation.getWorld().playSound(safeLocation, sound, safeCategory, safeVolume, safePitch);
            }
        });
    }

    private boolean cooldownBlocked(Plugin owner, UUID playerId, String cueKey, long cooldownMillis) {
        if (cooldownMillis <= 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        String key = ownerKey(owner) + "|" + playerId + "|" + cueKey;
        Long previous = cooldowns.get(key);
        if (previous != null && now - previous < cooldownMillis) {
            return true;
        }
        cooldowns.put(key, now);
        return false;
    }

    private OwnerSounds ownerSounds(Plugin owner) {
        return owners.computeIfAbsent(ownerKey(owner), ignored -> loadOwnerSounds(owner));
    }

    private OwnerSounds loadOwnerSounds(Plugin owner) {
        if (!(owner instanceof JavaPlugin javaPlugin)) {
            return new OwnerSounds(true, true, Map.of());
        }
        FileConfiguration config = javaPlugin.getConfig();
        boolean enabled = config.getBoolean("sounds.enabled", true);
        boolean logInvalid = config.getBoolean("sounds.log-invalid", true);
        Map<String, SoundCue> cues = new LinkedHashMap<>();

        ConfigurationSection cueSection = config.getConfigurationSection("sounds.cues");
        if (cueSection != null) {
            for (String key : cueSection.getKeys(false)) {
                String path = "sounds.cues." + key;
                SoundCue cue = readCue(owner, config, path, logInvalid);
                if (cue != null) {
                    cues.put(normalizeCue(key), cue);
                }
            }
        }

        ConfigurationSection legacySection = config.getConfigurationSection("sounds");
        if (legacySection != null) {
            for (String key : legacySection.getKeys(false)) {
                if (RESERVED_SOUND_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String path = "sounds." + key;
                if (config.isConfigurationSection(path) && config.contains(path + ".sound")) {
                    cues.putIfAbsent(normalizeCue(key), readCue(owner, config, path, logInvalid));
                } else if (config.isString(path)) {
                    cues.putIfAbsent(normalizeCue(key), readFlatCue(owner, config, path, logInvalid));
                }
            }
        }
        return new OwnerSounds(enabled, logInvalid, Map.copyOf(cues));
    }

    private SoundCue readCue(Plugin owner, FileConfiguration config, String path, boolean logInvalid) {
        boolean enabled = config.getBoolean(path + ".enabled", true);
        String rawSound = config.getString(path + ".sound", null);
        Sound sound = parseSound(owner, path + ".sound", rawSound, logInvalid);
        if (rawSound != null && rawSound.trim().equalsIgnoreCase("none")) {
            enabled = false;
        }
        SoundCategory category = parseCategory(owner, path + ".category", config.getString(path + ".category", "MASTER"), logInvalid);
        float volume = (float) clamp(config.getDouble(path + ".volume", 1.0D), 0.0D, 4.0D);
        float pitch = (float) clamp(config.getDouble(path + ".pitch", 1.0D), 0.5D, 2.0D);
        long cooldownMillis = Math.max(0L, config.getLong(path + ".cooldown-ms", 0L));
        int onlyLastSeconds = Math.max(0, config.getInt(path + ".only-last-seconds", 0));
        int intervalSeconds = Math.max(1, config.getInt(path + ".interval-seconds", 1));
        return new SoundCue(enabled, sound, category, volume, pitch, cooldownMillis, onlyLastSeconds, intervalSeconds);
    }

    private SoundCue readFlatCue(Plugin owner, FileConfiguration config, String path, boolean logInvalid) {
        String rawSound = config.getString(path, null);
        boolean enabled = rawSound == null || !rawSound.trim().equalsIgnoreCase("none");
        return new SoundCue(enabled, parseSound(owner, path, rawSound, logInvalid),
                SoundCategory.MASTER, 1.0F, 1.0F, 0L, 0, 1);
    }

    private Sound parseSound(Plugin owner, String path, String raw, boolean logInvalid) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return null;
        }
        try {
            return Sound.valueOf(normalizeEnum(raw));
        } catch (IllegalArgumentException ignored) {
            invalidSounds.incrementAndGet();
            warnInvalid(owner, path, raw, "sound", logInvalid);
            return null;
        }
    }

    private Sound parseRawSound(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return null;
        }
        try {
            return Sound.valueOf(normalizeEnum(raw));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private SoundCategory parseCategory(Plugin owner, String path, String raw, boolean logInvalid) {
        if (raw == null || raw.isBlank()) {
            return SoundCategory.MASTER;
        }
        try {
            return SoundCategory.valueOf(normalizeEnum(raw));
        } catch (IllegalArgumentException ignored) {
            warnInvalid(owner, path, raw, "sound category", logInvalid);
            return SoundCategory.MASTER;
        }
    }

    private void warnInvalid(Plugin owner, String path, String raw, String label, boolean logInvalid) {
        if (!logInvalid || owner == null) {
            return;
        }
        String key = ownerKey(owner) + "|" + path + "|" + raw;
        if (warnedInvalid.add(key)) {
            owner.getLogger().warning("[Sound] config.yml." + path + " invalid " + label + ": " + raw + ".");
        }
    }

    private String ownerKey(Plugin owner) {
        String value = owner == null ? platformPlugin.getName() : owner.getName();
        return ownerKeyCache.computeIfAbsent(value,
                name -> OWNER_KEY_SANITIZE.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("_"));
    }

    private String normalizeCue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String normalizeEnum(String value) {
        return value.trim().toUpperCase(Locale.ROOT)
                .replace("MINECRAFT:", "")
                .replace('.', '_')
                .replace('-', '_');
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record OwnerSounds(boolean enabled, boolean logInvalid, Map<String, SoundCue> cues) {
    }
}
