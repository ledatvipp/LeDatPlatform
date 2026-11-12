package vn.ledat.platform.api.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditRecord(
        String pluginId,
        String category,
        String action,
        UUID actor,
        UUID target,
        String result,
        String valueType,
        String value,
        Map<String, String> metadata,
        Instant timestamp
) {
    public AuditRecord {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
