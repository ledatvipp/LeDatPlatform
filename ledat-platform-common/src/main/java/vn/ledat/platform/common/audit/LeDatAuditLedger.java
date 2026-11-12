package vn.ledat.platform.common.audit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import vn.ledat.platform.common.scheduler.ScheduledTaskHandle;
import vn.ledat.platform.common.scheduler.SchedulerFacade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Shared lightweight audit ledger for economy/reward plugins.
 *
 * <p>Design goals for 400-500 players:</p>
 * <ul>
 *     <li>hot-path only sanitizes and offers to a bounded queue;</li>
 *     <li>file IO runs on async scheduler in batches;</li>
 *     <li>daily log files are written under the server root, not plugin data folders;</li>
 *     <li>old files are pruned by retention days and optional max-file count;</li>
 *     <li>shutdown flush is best-effort and bounded by queue size.</li>
 * </ul>
 */
public final class LeDatAuditLedger implements AutoCloseable {
    private static final DateTimeFormatter ISO_SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DEFAULT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Plugin plugin;
    private final SchedulerFacade scheduler;
    private final String pluginKey;
    private final String loggerPrefix;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong dropped = new AtomicLong(0L);
    private final AtomicLong lastDropWarnMillis = new AtomicLong(0L);
    private final AtomicLong lastRetentionMillis = new AtomicLong(0L);

    private volatile boolean enabled;
    private volatile boolean writeConsole;
    private volatile boolean logDroppedSummary;
    private volatile int maxQueueSize;
    private volatile int batchSize;
    private volatile long flushIntervalTicks;
    private volatile int retentionDays;
    private volatile int maxFiles;
    private volatile long dropWarnIntervalMillis;
    private volatile long retentionCheckIntervalMillis;
    private volatile Path folder;
    private volatile String filePrefix;
    private volatile DateTimeFormatter dateFormatter;
    private volatile ArrayBlockingQueue<AuditRecord> queue;
    private volatile ScheduledTaskHandle flushTask = ScheduledTaskHandle.NOOP;

    private LeDatAuditLedger(Plugin plugin, SchedulerFacade scheduler, String pluginKey, ConfigurationSection section) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginKey = sanitizeKey(pluginKey == null || pluginKey.isBlank() ? plugin.getName() : pluginKey);
        this.loggerPrefix = "[AuditLedger:" + this.pluginKey + "] ";
        reload(section);
    }

    public static LeDatAuditLedger create(Plugin plugin, SchedulerFacade scheduler, String pluginKey, ConfigurationSection section) {
        return new LeDatAuditLedger(plugin, scheduler, pluginKey, section);
    }

    public void reload(ConfigurationSection section) {
        boolean wasRunning = running.get();
        if (wasRunning) {
            flushTask.cancelQuietly(plugin.getLogger(), loggerPrefix + "reload");
        }

        this.enabled = getBoolean(section, "enabled", true);
        this.writeConsole = getBoolean(section, "write-console", false);
        this.logDroppedSummary = getBoolean(section, "log-dropped-summary", true);
        this.maxQueueSize = clamp(getInt(section, "max-queue-size", 50_000), 1_000, 1_000_000);
        this.batchSize = clamp(getInt(section, "batch-size", 1_000), 50, 50_000);
        long flushSeconds = clamp(getLong(section, "flush-interval-seconds", 5L), 1L, 300L);
        this.flushIntervalTicks = Math.max(20L, flushSeconds * 20L);
        this.retentionDays = clamp(getInt(section, "retention-days", 5), 1, 365);
        this.maxFiles = clamp(getInt(section, "max-files", 0), 0, 100_000);
        long dropWarnSeconds = clamp(getLong(section, "drop-warning-interval-seconds", 30L), 5L, 3600L);
        this.dropWarnIntervalMillis = dropWarnSeconds * 1000L;
        long retentionMinutes = clamp(getLong(section, "retention-check-interval-minutes", 30L), 1L, 1440L);
        this.retentionCheckIntervalMillis = retentionMinutes * 60_000L;
        this.filePrefix = sanitizeKey(getString(section, "file-prefix", "audit"));
        this.dateFormatter = parseDateFormatter(getString(section, "file-date-pattern", "yyyy-MM-dd"));
        this.folder = resolveFolder(getString(section, "folder", "logs/plugins/{plugin}"));
        this.queue = new ArrayBlockingQueue<>(this.maxQueueSize);

        if (enabled) {
            running.set(true);
            this.flushTask = scheduler.runAsyncRepeating(this::flushSafely, flushIntervalTicks, flushIntervalTicks);
            scheduler.runAsync(this::cleanupRetentionSafely);
            plugin.getLogger().info(loggerPrefix + "enabled folder=" + folder + " retention=" + retentionDays + "d queue=" + maxQueueSize);
        } else {
            running.set(false);
            this.flushTask = ScheduledTaskHandle.NOOP;
            plugin.getLogger().info(loggerPrefix + "disabled by config.");
        }
    }

    public boolean isEnabled() {
        return enabled && running.get();
    }

    public int queuedSize() {
        ArrayBlockingQueue<AuditRecord> activeQueue = queue;
        return activeQueue == null ? 0 : activeQueue.size();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public void log(String action, Map<String, ?> fields) {
        if (!isEnabled()) {
            return;
        }
        Map<String, ?> safeFields = fields == null ? Map.of() : fields;
        StringBuilder line = new StringBuilder(192);
        line.append(ISO_SECOND.format(LocalDateTime.now())).append('\t')
                .append("plugin=").append(pluginKey).append('\t')
                .append("action=").append(cleanValue(action));
        for (Map.Entry<String, ?> entry : safeFields.entrySet()) {
            String key = sanitizeKey(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            line.append('\t').append(key).append('=').append(cleanValue(String.valueOf(entry.getValue())));
        }
        offer(new AuditRecord(sanitizeKey(action), line.toString(), System.currentTimeMillis()));
    }

    public void logRaw(String channel, String line) {
        if (!isEnabled()) {
            return;
        }
        offer(new AuditRecord(sanitizeKey(channel), cleanLine(line), System.currentTimeMillis()));
    }

    public void flushNow() {
        flushSafely();
    }

    @Override
    public void close() {
        running.set(false);
        flushTask.cancelQuietly(plugin.getLogger(), loggerPrefix + "close");
        flushSafely();
        cleanupRetentionSafely();
        long droppedTotal = dropped.get();
        if (droppedTotal > 0L && logDroppedSummary) {
            plugin.getLogger().warning(loggerPrefix + "dropped " + droppedTotal + " audit entries because the queue was full.");
        }
    }

    private void offer(AuditRecord record) {
        ArrayBlockingQueue<AuditRecord> activeQueue = queue;
        if (activeQueue == null || record == null) {
            return;
        }
        if (!activeQueue.offer(record)) {
            long total = dropped.incrementAndGet();
            long now = System.currentTimeMillis();
            long last = lastDropWarnMillis.get();
            if (now - last >= dropWarnIntervalMillis && lastDropWarnMillis.compareAndSet(last, now)) {
                plugin.getLogger().warning(loggerPrefix + "queue full; dropped audit entry. droppedTotal=" + total
                        + " queueSize=" + activeQueue.size() + "/" + maxQueueSize);
            }
            return;
        }
        if (writeConsole) {
            plugin.getLogger().info(loggerPrefix + record.line());
        }
    }

    private void flushSafely() {
        try {
            flush();
        } catch (Throwable error) {
            plugin.getLogger().log(Level.WARNING, loggerPrefix + "flush failed", error);
        }
    }

    private void flush() throws IOException {
        ArrayBlockingQueue<AuditRecord> activeQueue = queue;
        if (activeQueue == null || activeQueue.isEmpty()) {
            maybeCleanupRetention();
            return;
        }

        List<AuditRecord> drained = new ArrayList<>(batchSize);
        activeQueue.drainTo(drained, batchSize);
        if (drained.isEmpty()) {
            maybeCleanupRetention();
            return;
        }

        Files.createDirectories(folder);
        Map<Path, List<String>> byFile = new HashMap<>();
        for (AuditRecord record : drained) {
            byFile.computeIfAbsent(fileFor(record), ignored -> new ArrayList<>()).add(record.line());
        }

        for (Map.Entry<Path, List<String>> batch : byFile.entrySet()) {
            Files.writeString(batch.getKey(), String.join(System.lineSeparator(), batch.getValue()) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        }
        maybeCleanupRetention();
    }

    private Path fileFor(AuditRecord record) {
        LocalDate date = Instant.ofEpochMilli(record.createdAtMillis()).atZone(ZoneId.systemDefault()).toLocalDate();
        String day = dateFormatter.format(date);
        String channel = sanitizeKey(record.channel());
        if (channel.isBlank()) {
            channel = "general";
        }
        return folder.resolve(filePrefix + "-" + channel + "-" + day + ".log").normalize();
    }

    private void maybeCleanupRetention() {
        long now = System.currentTimeMillis();
        long last = lastRetentionMillis.get();
        if (now - last >= retentionCheckIntervalMillis && lastRetentionMillis.compareAndSet(last, now)) {
            cleanupRetentionSafely();
        }
    }

    private void cleanupRetentionSafely() {
        try {
            cleanupRetention();
        } catch (Throwable error) {
            plugin.getLogger().log(Level.WARNING, loggerPrefix + "retention cleanup failed", error);
        }
    }

    private void cleanupRetention() throws IOException {
        if (folder == null || !Files.isDirectory(folder)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        List<Path> files;
        try (var stream = Files.list(folder)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(filePrefix + "-"))
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .sorted(Comparator.comparing(this::lastModifiedSafe).reversed())
                    .toList();
        }

        int index = 0;
        for (Path file : files) {
            long modified = Files.getLastModifiedTime(file).toMillis();
            boolean expiredByAge = modified < cutoff;
            boolean expiredByCount = maxFiles > 0 && index >= maxFiles;
            if (expiredByAge || expiredByCount) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException error) {
                    plugin.getLogger().warning(loggerPrefix + "cannot delete old log " + file + ": " + error.getMessage());
                }
            }
            index++;
        }
    }

    private Instant lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    private Path resolveFolder(String raw) {
        String value = raw == null || raw.isBlank() ? "logs/plugins/{plugin}" : raw.trim();
        value = value.replace("{plugin}", pluginKey).replace("{plugin_id}", pluginKey);
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().resolve(path);
        }
        return path.normalize();
    }

    private DateTimeFormatter parseDateFormatter(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return DEFAULT_DATE_FORMAT;
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            formatter.format(LocalDate.now());
            return formatter;
        } catch (RuntimeException error) {
            plugin.getLogger().warning(loggerPrefix + "invalid file-date-pattern '" + pattern + "'; using yyyy-MM-dd.");
            return DEFAULT_DATE_FORMAT;
        }
    }

    private static String cleanLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String cleanValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String sanitizeKey(String value) {
        if (value == null) {
            return "unknown";
        }
        String sanitized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    private static boolean getBoolean(ConfigurationSection section, String path, boolean fallback) {
        return section == null ? fallback : section.getBoolean(path, fallback);
    }

    private static int getInt(ConfigurationSection section, String path, int fallback) {
        return section == null ? fallback : section.getInt(path, fallback);
    }

    private static long getLong(ConfigurationSection section, String path, long fallback) {
        return section == null ? fallback : section.getLong(path, fallback);
    }

    private static String getString(ConfigurationSection section, String path, String fallback) {
        return section == null ? fallback : section.getString(path, fallback);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private record AuditRecord(String channel, String line, long createdAtMillis) {
    }
}
