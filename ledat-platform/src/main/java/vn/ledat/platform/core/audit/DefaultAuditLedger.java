package vn.ledat.platform.core.audit;

import org.bukkit.plugin.Plugin;
import vn.ledat.platform.api.audit.AuditLedger;
import vn.ledat.platform.api.audit.AuditRecord;
import vn.ledat.platform.api.database.DatabaseProvider;
import vn.ledat.platform.api.database.StorageMode;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DefaultAuditLedger implements AuditLedger {
    private static final java.util.regex.Pattern SENSITIVE_VALUE_PATTERN =
            java.util.regex.Pattern.compile("(?i)(password|token|secret|apikey|api-key)=([^,\\s]+)");
    private static final java.util.regex.Pattern UNSAFE_ID_PATTERN =
            java.util.regex.Pattern.compile("[^a-z0-9_.-]");

    private final Plugin plugin;
    private final DatabaseProvider database;
    private final Logger logger;

    public DefaultAuditLedger(Plugin plugin, DatabaseProvider database, Logger logger) {
        this.plugin = plugin;
        this.database = database;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<Void> record(AuditRecord record) {
        if (record == null || !plugin.getConfig().getBoolean("audit.enabled", false)
                || !plugin.getConfig().getBoolean("audit.write-enabled", false)) {
            return CompletableFuture.completedFuture(null);
        }
        AuditRecord safeRecord = sanitize(record);
        if (shouldUseYaml()) {
            return recordYaml(safeRecord);
        }
        return recordSql(safeRecord).exceptionallyCompose(error -> {
            logger.log(Level.WARNING, "Audit SQL write failed for " + safeRecord.pluginId() + "/" + safeRecord.action(), error);
            if (plugin.getConfig().getBoolean("audit.yaml-fallback", true)) {
                return recordYaml(safeRecord);
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    @Override
    public CompletableFuture<Boolean> recordVerified(AuditRecord record) {
        if (record == null || !plugin.getConfig().getBoolean("audit.enabled", false)
                || !plugin.getConfig().getBoolean("audit.write-enabled", false)) {
            return CompletableFuture.completedFuture(false);
        }
        AuditRecord safeRecord = sanitize(record);
        if (shouldUseYaml()) {
            return recordYamlVerified(safeRecord);
        }
        return recordSql(safeRecord)
                .thenCompose(ignored -> verifySql(safeRecord))
                .exceptionallyCompose(error -> {
                    logger.log(Level.WARNING, "Verified audit SQL write failed for "
                            + safeRecord.pluginId() + "/" + safeRecord.action(), error);
                    if (!plugin.getConfig().getBoolean("audit.yaml-fallback", true)) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return recordYamlVerified(safeRecord);
                });
    }

    @Override
    public CompletableFuture<Void> recordAll(List<AuditRecord> records) {
        if (records == null || records.isEmpty() || !plugin.getConfig().getBoolean("audit.enabled", false)
                || !plugin.getConfig().getBoolean("audit.write-enabled", false)) {
            return CompletableFuture.completedFuture(null);
        }
        List<AuditRecord> safeRecords = records.stream().filter(java.util.Objects::nonNull).map(this::sanitize).toList();
        if (safeRecords.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (shouldUseYaml()) {
            return CompletableFuture.allOf(safeRecords.stream().map(this::recordYaml).toArray(CompletableFuture[]::new));
        }
        return recordSqlBatch(safeRecords).exceptionallyCompose(error -> {
            logger.log(Level.WARNING, "Audit SQL batch write failed for " + safeRecords.size() + " row(s)", error);
            if (!plugin.getConfig().getBoolean("audit.yaml-fallback", true)) {
                return CompletableFuture.failedFuture(error);
            }
            return CompletableFuture.allOf(safeRecords.stream().map(this::recordYaml).toArray(CompletableFuture[]::new));
        });
    }

    @Override
    public CompletableFuture<Void> economy(String pluginId, String action, UUID actor, UUID target, String currency, BigDecimal amount, String result, Map<String, String> metadata) {
        return record(new AuditRecord(pluginId, "economy", action, actor, target, result, currency, amount == null ? "0" : amount.toPlainString(), metadata, null));
    }

    @Override
    public CompletableFuture<Void> item(String pluginId, String action, UUID actor, String itemType, int amount, String result, Map<String, String> metadata) {
        return record(new AuditRecord(pluginId, "item", action, actor, null, result, itemType, String.valueOf(amount), metadata, null));
    }

    @Override
    public void flush() {
        if (!plugin.getConfig().getBoolean("audit.enabled", false)
                || !plugin.getConfig().getBoolean("audit.write-enabled", false)) {
            return;
        }
        database.yamlStore("platform_audit").ifPresent(store -> {
            try {
                store.save().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable error) {
                logger.warning("Audit YAML flush failed: " + safe(error.getMessage()));
            }
        });
    }

    @Override
    public void reload() {
        // stateless; reads config live.
    }

    private boolean shouldUseYaml() {
        return database.mode() == StorageMode.YAML || !database.enabled() || !database.healthy();
    }

    private CompletableFuture<Void> recordSql(AuditRecord record) {
        return database.execute(record.pluginId(), connection -> {
            String sql = "INSERT INTO " + database.tableName("platform", "audit_ledger") +
                    " (plugin_id, category, action, actor, target, result, value_type, value, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.pluginId());
                statement.setString(2, record.category());
                statement.setString(3, record.action());
                statement.setString(4, record.actor() == null ? null : record.actor().toString());
                statement.setString(5, record.target() == null ? null : record.target().toString());
                statement.setString(6, record.result());
                statement.setString(7, record.valueType());
                statement.setString(8, record.value());
                statement.setString(9, metadata(record.metadata()));
                statement.setLong(10, record.timestamp().toEpochMilli());
                statement.executeUpdate();
            }
        });
    }

    private CompletableFuture<Void> recordSqlBatch(List<AuditRecord> records) {
        return database.transaction("platform-audit-batch", connection -> {
            String sql = "INSERT INTO " + database.tableName("platform", "audit_ledger") +
                    " (plugin_id, category, action, actor, target, result, value_type, value, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int batchSize = Math.max(1, plugin.getConfig().getInt("audit.sql-batch-size", 250));
                int pending = 0;
                for (AuditRecord record : records) {
                    bind(statement, record);
                    statement.addBatch();
                    if (++pending >= batchSize) {
                        statement.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    statement.executeBatch();
                }
            }
        });
    }

    private void bind(PreparedStatement statement, AuditRecord record) throws java.sql.SQLException {
        statement.setString(1, record.pluginId());
        statement.setString(2, record.category());
        statement.setString(3, record.action());
        statement.setString(4, record.actor() == null ? null : record.actor().toString());
        statement.setString(5, record.target() == null ? null : record.target().toString());
        statement.setString(6, record.result());
        statement.setString(7, record.valueType());
        statement.setString(8, record.value());
        statement.setString(9, metadata(record.metadata()));
        statement.setLong(10, record.timestamp().toEpochMilli());
    }

    private CompletableFuture<Void> recordYaml(AuditRecord record) {
        String key = yamlKey(record);
        return database.yamlStore("platform_audit").orElseThrow().set(key, serialize(record));
    }

    private CompletableFuture<Boolean> recordYamlVerified(AuditRecord record) {
        return database.yamlStore("platform_audit")
                .map(store -> {
                    String key = yamlKey(record);
                    return store.set(key, serialize(record))
                            .thenCompose(ignored -> store.save())
                            .thenApply(ignored -> verifyYamlFile(store.file(), key, record))
                            .exceptionally(error -> false);
                })
                .orElseGet(() -> CompletableFuture.completedFuture(false));
    }

    private CompletableFuture<Boolean> verifySql(AuditRecord record) {
        return database.query("platform-audit-verify", connection -> {
            String sql = "SELECT 1 FROM " + database.tableName("platform", "audit_ledger")
                    + " WHERE plugin_id = ? AND category = ? AND action = ?"
                    + " AND ((actor = ?) OR (actor IS NULL AND ? IS NULL))"
                    + " AND ((target = ?) OR (target IS NULL AND ? IS NULL))"
                    + " AND result = ? AND value_type = ? AND value = ? AND metadata = ? AND created_at = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.pluginId());
                statement.setString(2, record.category());
                statement.setString(3, record.action());
                String actor = record.actor() == null ? null : record.actor().toString();
                String target = record.target() == null ? null : record.target().toString();
                statement.setString(4, actor);
                statement.setString(5, actor);
                statement.setString(6, target);
                statement.setString(7, target);
                statement.setString(8, record.result());
                statement.setString(9, record.valueType());
                statement.setString(10, record.value());
                statement.setString(11, metadata(record.metadata()));
                statement.setLong(12, record.timestamp().toEpochMilli());
                try (var result = statement.executeQuery()) {
                    return result.next();
                }
            }
        }).exceptionally(error -> false);
    }

    private String yamlKey(AuditRecord record) {
        return record.timestamp().toEpochMilli() + "." + safeId(record.pluginId()) + "."
                + safeId(record.category()) + "." + safeId(record.action()) + "." + java.util.UUID.randomUUID();
    }

    private boolean verifyYamlFile(java.io.File file, String key, AuditRecord record) {
        if (file == null || !file.isFile()) {
            return false;
        }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        String path = key + ".";
        if (!record.pluginId().equals(yaml.getString(path + "plugin"))
                || !record.category().equals(yaml.getString(path + "category"))
                || !record.action().equals(yaml.getString(path + "action"))
                || !identity(record.actor()).equals(yaml.getString(path + "actor", ""))
                || !identity(record.target()).equals(yaml.getString(path + "target", ""))
                || !record.result().equals(yaml.getString(path + "result"))
                || !record.valueType().equals(yaml.getString(path + "value-type"))
                || !record.value().equals(yaml.getString(path + "value"))
                || !record.timestamp().toString().equals(yaml.getString(path + "timestamp"))) {
            return false;
        }
        org.bukkit.configuration.ConfigurationSection section =
                yaml.getConfigurationSection(path + "metadata");
        if (record.metadata().isEmpty()) {
            return section == null || section.getKeys(true).isEmpty();
        }
        if (section == null) {
            return false;
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (String metadataKey : section.getKeys(true)) {
            if (!section.isConfigurationSection(metadataKey)) {
                actual.put(metadataKey, String.valueOf(section.get(metadataKey)));
            }
        }
        return record.metadata().equals(actual);
    }

    private String identity(UUID value) {
        return value == null ? "" : value.toString();
    }

    private AuditRecord sanitize(AuditRecord record) {
        String pluginId = safeBound(record.pluginId(), "unknown", 64);
        String category = safeBound(record.category(), "general", 64);
        String action = safeBound(record.action(), "unknown", 96);
        String result = safeBound(record.result(), "unknown", 64);
        String valueType = safeBound(record.valueType(), "text", 64);
        String value = safeBound(record.value(), "", 512);
        Map<String, String> metadata = new LinkedHashMap<>();
        int limit = Math.max(1, plugin.getConfig().getInt("audit.metadata.max-entries", 32));
        int count = 0;
        for (Map.Entry<String, String> entry : record.metadata().entrySet()) {
            if (++count > limit) {
                break;
            }
            metadata.put(safeBound(entry.getKey(), "key", 64), safeBound(entry.getValue(), "", 256));
        }
        return new AuditRecord(pluginId, category, action, record.actor(), record.target(), result, valueType, value, metadata, record.timestamp());
    }

    private Map<String, Object> serialize(AuditRecord record) {
        return Map.of(
                "plugin", record.pluginId(),
                "category", record.category(),
                "action", record.action(),
                "actor", record.actor() == null ? "" : record.actor().toString(),
                "target", record.target() == null ? "" : record.target().toString(),
                "result", record.result() == null ? "" : record.result(),
                "value-type", record.valueType() == null ? "" : record.valueType(),
                "value", record.value() == null ? "" : record.value(),
                "metadata", record.metadata(),
                "timestamp", record.timestamp().toString()
        );
    }

    private String metadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(json(entry.getKey())).append('"').append(':').append('"').append(json(entry.getValue())).append('"');
        }
        return builder.append('}').toString();
    }

    private String json(String value) {
        return String.valueOf(value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String safeBound(String value, String fallback, int max) {
        String safe = value == null || value.isBlank() ? fallback : value;
        safe = SENSITIVE_VALUE_PATTERN.matcher(safe).replaceAll("$1=***");
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private String safeId(String value) {
        return UNSAFE_ID_PATTERN.matcher(safeBound(value, "unknown", 64).toLowerCase(java.util.Locale.ROOT)).replaceAll("_");
    }

    private String safe(String value) {
        return value == null ? "unknown" : SENSITIVE_VALUE_PATTERN.matcher(value).replaceAll("$1=***");
    }
}
