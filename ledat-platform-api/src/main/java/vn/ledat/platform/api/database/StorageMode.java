package vn.ledat.platform.api.database;

import java.util.Locale;

public enum StorageMode {
    SHARED_MYSQL,
    MYSQL,
    SQLITE,
    YAML;

    public static StorageMode parse(String value, StorageMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        if (normalized.equals("LOCAL")) {
            return YAML;
        }
        if (normalized.equals("MARIADB")) {
            return MYSQL;
        }
        try {
            return StorageMode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public boolean sql() {
        return this == SHARED_MYSQL || this == MYSQL || this == SQLITE;
    }
}
