package vn.ledat.platform.common.config;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Shared conservative runtime config validator for LeDat plugins.
 *
 * <p>The validator never mutates admin files. It only logs clear path-level
 * warnings and returns safe fallbacks for runtime getters. Plugin-specific
 * loaders should call the recursive validators on enable/reload so invalid
 * material/sound/slot/size values are reported once at reload time instead of
 * from hot event or GUI paths.</p>
 */
public final class LeDatConfigValidator {
    private LeDatConfigValidator() {
    }

    public static Material material(JavaPlugin plugin, String source, String path, String raw, Material fallback) {
        Material parsed = Material.matchMaterial(raw == null ? "" : raw.trim());
        if (parsed == null) {
            warn(plugin, source, path, "invalid material: '" + raw + "'; fallback " + fallback.name() + ".");
            return fallback;
        }
        return parsed;
    }

    public static Sound sound(JavaPlugin plugin, String source, String path, String raw, Sound fallback) {
        Sound parsed = parseSound(raw);
        if (parsed == null) {
            warn(plugin, source, path, "invalid sound: '" + raw + "'; fallback " + fallback.name() + ".");
            return fallback;
        }
        return parsed;
    }

    public static int boundedInt(JavaPlugin plugin, String source, String path, int value, int fallback, int min, int max) {
        if (value < min || value > max) {
            warn(plugin, source, path, "out of range: " + value + " (allowed " + min + ".." + max + "); fallback " + fallback + ".");
            return fallback;
        }
        return value;
    }

    public static long boundedLong(JavaPlugin plugin, String source, String path, long value, long fallback, long min, long max) {
        if (value < min || value > max) {
            warn(plugin, source, path, "out of range: " + value + " (allowed " + min + ".." + max + "); fallback " + fallback + ".");
            return fallback;
        }
        return value;
    }

    public static double boundedDouble(JavaPlugin plugin, String source, String path, double value, double fallback, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max) {
            warn(plugin, source, path, "out of range: " + value + " (allowed " + min + ".." + max + "); fallback " + fallback + ".");
            return fallback;
        }
        return value;
    }

    public static int guiSize(JavaPlugin plugin, String source, String path, int value, int fallback) {
        if (value < 9 || value > 54 || value % 9 != 0) {
            warn(plugin, source, path, "invalid GUI size: " + value + "; must be a multiple of 9 from 9 to 54; fallback " + fallback + ".");
            return fallback;
        }
        return value;
    }

    public static int guiSlot(JavaPlugin plugin, String source, String path, int value, int fallback, int size) {
        int max = Math.max(0, size - 1);
        if (value < 0 || value > max) {
            warn(plugin, source, path, "invalid GUI slot: " + value + "; allowed 0.." + max + "; fallback " + fallback + ".");
            return fallback;
        }
        return value;
    }

    public static String mode(JavaPlugin plugin, String source, String path, String raw, String fallback, Set<String> allowed) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!allowed.contains(normalized)) {
            warn(plugin, source, path, "invalid mode: '" + raw + "'; allowed " + allowed + "; fallback " + fallback + ".");
            return fallback;
        }
        return normalized;
    }

    public static void validateGuiTree(JavaPlugin plugin, String source, FileConfiguration config, String rootPath) {
        ConfigurationSection root = config == null ? null : config.getConfigurationSection(rootPath);
        if (root != null) {
            validateGuiTree(plugin, source, root, rootPath, null);
        }
    }

    public static void validateGuiTree(JavaPlugin plugin, String source, ConfigurationSection section, String path, Integer inheritedSize) {
        if (section == null) {
            return;
        }
        Integer localSize = inheritedSize;
        if (section.contains("size")) {
            if (section.isInt("size")) {
                int configured = section.getInt("size");
                localSize = guiSize(plugin, source, path + ".size", configured, inheritedSize == null ? 54 : inheritedSize);
            } else {
                warn(plugin, source, path + ".size", "GUI size must be an integer.");
            }
        }
        for (String key : section.getKeys(false)) {
            String childPath = path + "." + key;
            Object value = section.get(key);
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if ("slot".equals(normalizedKey)) {
                if (value instanceof Number number) {
                    int slot = number.intValue();
                    int size = localSize == null ? 54 : localSize;
                    guiSlot(plugin, source, childPath, slot, Math.max(0, Math.min(slot, size - 1)), size);
                } else if (value != null) {
                    warn(plugin, source, childPath, "GUI slot must be an integer.");
                }
            } else if (isSlotListKey(normalizedKey)) {
                validateSlotList(plugin, source, childPath, value, localSize == null ? 54 : localSize);
            }
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                validateGuiTree(plugin, source, child, childPath, localSize);
            }
        }
    }

    public static void validateMaterialsRecursive(JavaPlugin plugin, String source, ConfigurationSection section, String path) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String childPath = path + "." + key;
            Object value = section.get(key);
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (value instanceof String raw && isMaterialKey(normalizedKey)) {
                if (Material.matchMaterial(raw.trim()) == null) {
                    warn(plugin, source, childPath, "invalid material: '" + raw + "'. Feature/item may use fallback or be skipped.");
                }
            }
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                validateMaterialsRecursive(plugin, source, child, childPath);
            }
        }
    }

    public static void validateSoundsRecursive(JavaPlugin plugin, String source, ConfigurationSection section, String path) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String childPath = path + "." + key;
            Object value = section.get(key);
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (value instanceof String raw && isSoundKey(normalizedKey) && parseSound(raw) == null) {
                warn(plugin, source, childPath, "invalid sound: '" + raw + "'. Cue may be disabled or fallback will be used.");
            }
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                validateSoundsRecursive(plugin, source, child, childPath);
            }
        }
    }

    private static void validateSlotList(JavaPlugin plugin, String source, String path, Object value, int size) {
        if (!(value instanceof Collection<?> values)) {
            if (value != null) {
                warn(plugin, source, path, "GUI slot list must be a YAML list of integers or ranges like 10-16.");
            }
            return;
        }
        int index = 0;
        for (Object raw : values) {
            List<Integer> slots = parseSlots(raw);
            if (slots == null) {
                warn(plugin, source, path + "[" + index + "]", "GUI slot list value must be an integer or range like 10-16.");
            } else {
                for (int slot : slots) {
                    if (slot < 0 || slot >= size) {
                        warn(plugin, source, path + "[" + index + "]", "GUI slot " + slot + " outside size " + size + ".");
                    }
                }
            }
            index++;
        }
    }

    private static List<Integer> parseSlots(Object raw) {
        if (raw instanceof Number number) {
            return List.of(number.intValue());
        }
        if (!(raw instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.matches("\\d+")) {
            return List.of(Integer.parseInt(trimmed));
        }
        if (trimmed.matches("\\d+\\s*-\\s*\\d+")) {
            String[] parts = trimmed.split("-", 2);
            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());
            if (end < start) {
                return null;
            }
            List<Integer> out = new ArrayList<>();
            for (int slot = start; slot <= end; slot++) {
                out.add(slot);
            }
            return out;
        }
        return null;
    }

    private static Sound parseSound(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Sound.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean isSlotListKey(String key) {
        return key.equals("slots") || key.endsWith("-slots") || key.endsWith("_slots") || key.equals("content-slots") || key.equals("item-slots");
    }

    private static boolean isMaterialKey(String key) {
        return key.equals("material") || key.equals("icon") || key.endsWith("-material") || key.endsWith("_material") || key.endsWith("material");
    }

    private static boolean isSoundKey(String key) {
        return key.equals("sound") || key.endsWith("-sound") || key.endsWith("_sound") || key.endsWith("sound");
    }

    private static void warn(JavaPlugin plugin, String source, String path, String message) {
        Logger logger = plugin.getLogger();
        logger.warning("[ConfigValidator] " + source + "." + path + " — " + message);
    }
}
