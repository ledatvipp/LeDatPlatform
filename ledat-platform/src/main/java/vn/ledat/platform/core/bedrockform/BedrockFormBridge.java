package vn.ledat.platform.core.bedrockform;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

public interface BedrockFormBridge {
    boolean available();
    boolean floodgatePluginDetected();
    boolean geyserPluginDetected();
    boolean floodgateClassDetected();
    boolean geyserApiClassDetected();
    boolean cumulusClassDetected();
    String status();
    boolean isBedrock(UUID playerId);
    boolean send(Player player, RenderedBedrockForm form, BridgeResponseHandler handler);

    interface BridgeResponseHandler {
        void valid(int clickedButtonIndex, Map<String, Object> inputs, Object source);
        void closed(Object source);
        void invalid(Object source);
    }

    record Detection(
            boolean geyserPluginDetected,
            boolean floodgatePluginDetected,
            boolean floodgateClassDetected,
            boolean geyserApiClassDetected,
            boolean cumulusClassDetected,
            ClassLoader classLoader,
            String detail
    ) {
        boolean available() {
            return (floodgateClassDetected || geyserApiClassDetected) && cumulusClassDetected && classLoader != null;
        }
    }

    final class Missing implements BedrockFormBridge {
        private final Detection detection;

        Missing(Detection detection) {
            this.detection = detection;
        }

        @Override public boolean available() { return false; }
        @Override public boolean floodgatePluginDetected() { return detection.floodgatePluginDetected(); }
        @Override public boolean geyserPluginDetected() { return detection.geyserPluginDetected(); }
        @Override public boolean floodgateClassDetected() { return detection.floodgateClassDetected(); }
        @Override public boolean geyserApiClassDetected() { return detection.geyserApiClassDetected(); }
        @Override public boolean cumulusClassDetected() { return detection.cumulusClassDetected(); }
        @Override public String status() { return detection.detail(); }
        @Override public boolean isBedrock(UUID playerId) { return false; }
        @Override public boolean send(Player player, RenderedBedrockForm form, BridgeResponseHandler handler) { return false; }
    }
}
