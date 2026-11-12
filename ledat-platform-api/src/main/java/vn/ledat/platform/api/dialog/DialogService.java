package vn.ledat.platform.api.dialog;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public interface DialogService {
    PlatformDialog.Builder dialog(Plugin plugin, String dialogId);

    void open(Player player, PlatformDialog dialog);

    /** Guarded convenience entrypoint for plugins that want an explicit availability result. */
    default boolean tryOpen(Player player, PlatformDialog dialog) {
        if (player == null || dialog == null || !canOpen(player)) return false;
        open(player, dialog);
        return true;
    }

    /**
     * Open a YAML-backed dialog template from plugins/<Plugin>/dialogs/<templateId>.yml.
     * The implementation may copy the same path from the plugin jar when present.
     * Callback keys override configured action lists with the same button id.
     */
    default void openTemplate(Player player, Plugin plugin, String templateId, Map<String, String> placeholders, Map<String, Consumer<DialogClick>> callbacks) {
        openTemplate(player, plugin, templateId, placeholders, callbacks, close -> { });
    }

    /**
     * Open a YAML-backed dialog and attach a close handler. Useful for purchase
     * confirmations and other temporary states where child plugins must cleanup
     * pending transactions when the player manually closes the fallback menu.
     */
    default void openTemplate(Player player, Plugin plugin, String templateId, Map<String, String> placeholders,
                              Map<String, Consumer<DialogClick>> callbacks, Consumer<DialogClose> closeHandler) {
        PlatformDialog dialog = dialog(plugin, templateId).title(Component.text(templateId))
                .onClose(closeHandler == null ? close -> { } : closeHandler)
                .build();
        open(player, dialog);
    }

    default void reloadTemplates(Plugin plugin) { }
    default int templateCount(Plugin plugin) { return 0; }

    /**
     * Render admin-controlled dialog text with platform MiniMessage and rich object tags.
     * Runtime/player placeholders remain escaped; tags such as {@code <item:diamond>} and
     * {@code <head:playerName>} are resolved by the implementation when supported.
     */
    default Component renderText(String mini, Map<String, String> placeholders) {
        return Component.text(mini == null ? "" : mini);
    }

    /** Create an Adventure object sprite component for a material id when runtime supports it. */
    default Component itemSprite(String materialName) {
        return Component.text(materialName == null ? "" : materialName);
    }

    /** Create an Adventure object player-head component when runtime supports it. */
    default Component playerHead(String playerNameOrUuid) {
        return Component.text(playerNameOrUuid == null ? "" : playerNameOrUuid);
    }

    /** True when Adventure object components are available for sprite/head rich tags. */
    default boolean richObjectComponentsAvailable() { return false; }

    default void confirm(Player player, Plugin plugin, String dialogId, Component title, List<Component> body,
                         Consumer<DialogClick> onConfirm, Consumer<DialogClick> onCancel) {
        PlatformDialog dialog = dialog(plugin, dialogId)
                .title(title)
                .body(body)
                .button(DialogButton.confirm(Component.text("Confirm"), Component.empty(), onConfirm))
                .button(DialogButton.cancel(Component.text("Cancel"), Component.empty(), onCancel))
                .build();
        open(player, dialog);
    }

    default void confirm(Player player, Plugin plugin, String dialogId, Component title, List<Component> body,
                         Consumer<DialogClick> onConfirm, Consumer<DialogClick> onCancel, Consumer<DialogClose> onClose) {
        PlatformDialog dialog = dialog(plugin, dialogId)
                .title(title)
                .body(body)
                .button(DialogButton.confirm(Component.text("Confirm"), Component.empty(), onConfirm))
                .button(DialogButton.cancel(Component.text("Cancel"), Component.empty(), onCancel))
                .onClose(onClose)
                .build();
        open(player, dialog);
    }

    default void notice(Player player, Plugin plugin, String dialogId, Component title, List<Component> body,
                        Consumer<DialogClick> onClose) {
        open(player, dialog(plugin, dialogId).title(title).body(body)
                .button(DialogButton.action("close", Component.text("Close"), Component.empty(), 13,
                        org.bukkit.Material.BARRIER, onClose)).build());
    }

    void close(Player player, boolean clearSession);

    boolean nativeDialogsAvailable();
    boolean fallbackEnabled();
    boolean canUseNative(Player player);

    /**
     * True when the platform can open either a native Paper dialog or the guarded
     * inventory fallback for this player. Child plugins should prefer this over
     * checking {@link #canUseNative(Player)} when deciding whether to call
     * {@link #open(Player, PlatformDialog)} or {@link #openTemplate(Player, Plugin, String, Map, Map)}.
     */
    default boolean canOpen(Player player) {
        return fallbackEnabled() || canUseNative(player);
    }

    String nativeStatus();
    String clientStatus(Player player);

    /**
     * Override native/fallback dialog routing for a single player. Child plugins can use this
     * for player preferences such as "always use inventory GUI" without adding custom checks
     * around every dialog call.
     *
     * <p>{@code null} or {@link DialogDisplayMode#AUTO} clears the explicit override and returns
     * the player to the dialog/template mode.</p>
     */
    default void setDisplayModeOverride(UUID playerId, DialogDisplayMode mode) { }

    /** Current player-level display override, or {@code null} when template/default mode applies. */
    default DialogDisplayMode displayModeOverride(UUID playerId) { return null; }

    /** Clear a player-level dialog display override. */
    default void clearDisplayModeOverride(UUID playerId) { setDisplayModeOverride(playerId, null); }

    /** Read-only snapshot of active player-level dialog display overrides. */
    default Map<UUID, DialogDisplayMode> displayModeOverrides() { return Map.of(); }

    DialogSession session(UUID playerId);
    int sessionCount();
    int sessionCount(Plugin plugin);
    void cleanup(UUID playerId);
    void cleanup(Plugin plugin);
    int cleanupExpired();

    /**
     * Runtime snapshot for dialog pressure/leak diagnostics.
     */
    default DialogStats stats() {
        return new DialogStats(0, 0, 0, 0, 0L, 0L, 0L);
    }

    default Map<String, String> diagnostics(Player player) {
        return Map.of(
                "native", String.valueOf(nativeDialogsAvailable()),
                "fallback", String.valueOf(fallbackEnabled()),
                "client", clientStatus(player),
                "status", nativeStatus()
        );
    }

    record DialogStats(
            int sessions,
            int nativeActions,
            int displayOverrides,
            int templateOwnerCacheSize,
            long expiredSessionCleanups,
            long nativeFallbacks,
            long failedOpens
    ) {
    }
}
