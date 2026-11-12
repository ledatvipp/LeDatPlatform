package vn.ledat.platform.api.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;

public final class CommandContext {
    private final Plugin plugin;
    private final CommandSender sender;
    private final String label;
    private final String[] args;

    public CommandContext(Plugin plugin, CommandSender sender, String label, String[] args) {
        this.plugin = plugin;
        this.sender = sender;
        this.label = label;
        this.args = args == null ? new String[0] : args;
    }

    public Plugin plugin() { return plugin; }
    public CommandSender sender() { return sender; }
    public String label() { return label; }
    public String[] args() { return args.clone(); }
    public int length() { return args.length; }
    public boolean has(int index) { return index >= 0 && index < args.length; }
    public String arg(int index) { return has(index) ? args[index] : ""; }
    public String arg(int index, String fallback) { return has(index) ? args[index] : fallback; }
    public List<String> argList() { return Arrays.asList(args.clone()); }
    public boolean isPlayer() { return sender instanceof Player; }
    public Optional<Player> player() { return sender instanceof Player player ? Optional.of(player) : Optional.empty(); }
    public int intArg(int index, int fallback) {
        try { return Integer.parseInt(arg(index)); } catch (Exception ignored) { return fallback; }
    }
    public long longArg(int index, long fallback) {
        try { return Long.parseLong(arg(index)); } catch (Exception ignored) { return fallback; }
    }
    public double doubleArg(int index, double fallback) {
        try { return Double.parseDouble(arg(index)); } catch (Exception ignored) { return fallback; }
    }

    public boolean booleanArg(int index, boolean fallback) {
        String value = arg(index).trim();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes") || value.equals("1")) return true;
        if (value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no") || value.equals("0")) return false;
        return fallback;
    }

    public Optional<UUID> uuidArg(int index) {
        try { return Optional.of(UUID.fromString(arg(index))); } catch (Exception ignored) { return Optional.empty(); }
    }

    public Optional<Material> materialArg(int index) {
        return Optional.ofNullable(Material.matchMaterial(arg(index)));
    }

    public Optional<World> worldArg(int index) {
        return Optional.ofNullable(Bukkit.getWorld(arg(index)));
    }

    public Optional<Player> onlinePlayerArg(int index) {
        return Optional.ofNullable(Bukkit.getPlayerExact(arg(index)));
    }

    public Optional<OfflinePlayer> offlinePlayerArg(int index) {
        String value = arg(index);
        return value.isBlank() ? Optional.empty() : Optional.of(Bukkit.getOfflinePlayer(value));
    }

    public Duration durationArg(int index, Duration fallback) {
        String raw = arg(index).trim().toLowerCase(java.util.Locale.ROOT);
        if (raw.isBlank()) return fallback;
        try {
            long multiplier = 1L;
            char suffix = raw.charAt(raw.length() - 1);
            String number = raw;
            if (Character.isLetter(suffix)) {
                number = raw.substring(0, raw.length() - 1);
                multiplier = switch (suffix) {
                    case 's' -> 1L;
                    case 'm' -> 60L;
                    case 'h' -> 3600L;
                    case 'd' -> 86400L;
                    case 'w' -> 604800L;
                    default -> throw new IllegalArgumentException("unknown duration suffix");
                };
            }
            return Duration.ofSeconds(Math.multiplyExact(Long.parseLong(number), multiplier));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public <E extends Enum<E>> Optional<E> enumArg(int index, Class<E> enumType) {
        if (enumType == null) return Optional.empty();
        try { return Optional.of(Enum.valueOf(enumType, arg(index).toUpperCase(java.util.Locale.ROOT))); }
        catch (Exception ignored) { return Optional.empty(); }
    }
}
