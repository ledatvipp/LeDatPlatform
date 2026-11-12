package vn.ledat.platform.api.item;

import java.util.Locale;

/** Provider-qualified custom item identifier, for example itemsadder:namespace:item or mmoitems:SWORD:FLAME_BLADE. */
public record CustomItemKey(String provider, String namespace, String id) {
    public CustomItemKey {
        provider = normalize(provider);
        namespace = normalize(namespace);
        id = normalize(id);
        if (provider.isBlank() || id.isBlank()) {
            throw new IllegalArgumentException("Custom item provider and id cannot be blank");
        }
    }

    public static CustomItemKey parse(String value) {
        String[] parts = value == null ? new String[0] : value.trim().split(":", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Expected provider:id or provider:namespace:id");
        }
        return parts.length == 2
                ? new CustomItemKey(parts[0], "", parts[1])
                : new CustomItemKey(parts[0], parts[1], parts[2]);
    }

    public String externalId() {
        return namespace.isBlank() ? id : namespace + ":" + id;
    }

    public String canonical() {
        return namespace.isBlank() ? provider + ":" + id : provider + ":" + namespace + ":" + id;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
