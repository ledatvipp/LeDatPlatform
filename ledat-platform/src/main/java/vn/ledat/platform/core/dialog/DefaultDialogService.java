package vn.ledat.platform.core.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.platform.api.dialog.DialogButton;
import vn.ledat.platform.api.dialog.DialogClick;
import vn.ledat.platform.api.dialog.DialogClose;
import vn.ledat.platform.api.dialog.DialogDisplayMode;
import vn.ledat.platform.api.dialog.DialogInputSpec;
import vn.ledat.platform.api.dialog.DialogInputType;
import vn.ledat.platform.api.dialog.DialogService;
import vn.ledat.platform.api.dialog.DialogSession;
import vn.ledat.platform.api.dialog.PlatformDialog;
import vn.ledat.platform.api.bedrockform.BedrockFormClick;
import vn.ledat.platform.api.bedrockform.BedrockFormResult;
import vn.ledat.platform.api.bedrockform.BedrockFormService;
import vn.ledat.platform.api.gui.GuiClick;
import vn.ledat.platform.api.gui.GuiService;
import vn.ledat.platform.api.language.LanguageService;
import vn.ledat.platform.api.scheduler.LeDatScheduler;
import vn.ledat.platform.api.sound.SoundCue;
import vn.ledat.platform.api.sound.SoundService;
import vn.ledat.platform.core.bedrockform.BedrockItemTextProjection;
import vn.ledat.platform.core.bedrockform.BedrockTextRenderer;
import vn.ledat.platform.core.bedrockform.DefaultBedrockFormService;
import vn.ledat.platform.core.gui.InventoryTitleNormalizer;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Smart Dialog service. It renders Paper native dialogs when possible and a
 * guarded inventory GUI fallback otherwise. Child plugins should keep all dialog
 * layout in dialogs/*.yml or inventories/*.yml and only provide callbacks.
 */
public final class DefaultDialogService implements DialogService, Listener {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final Plugin platformPlugin;
    private final GuiService guis;
    private final LanguageService languages;
    private final LeDatScheduler scheduler;
    private final SoundService sounds;
    private final BedrockFormService forms;
    private final NativeDialogBridge nativeBridge;
    private final DialogRichTextRenderer richText;
    private final BedrockTextRenderer bedrockText;
    private final ViaVersionClientVersionDetector clientDetector;
    private final Map<UUID, DialogSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingAction> nativeActions = new ConcurrentHashMap<>();
    private final Map<UUID, DialogDisplayMode> displayModeOverrides = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bedrockClientCache = new ConcurrentHashMap<>();
    private final Map<String, CachedTemplate> templateCache = new ConcurrentHashMap<>();
    private final Set<String> templatesSavedForPlugin = ConcurrentHashMap.newKeySet();
    private final AtomicLong expiredSessionCleanups = new AtomicLong();
    private final AtomicLong nativeFallbacks = new AtomicLong();
    private final AtomicLong failedOpens = new AtomicLong();

    public DefaultDialogService(Plugin platformPlugin, GuiService guis, LanguageService languages, LeDatScheduler scheduler, SoundService sounds) {
        this(platformPlugin, guis, languages, scheduler, sounds, BedrockFormService.disabled());
    }

    public DefaultDialogService(Plugin platformPlugin, GuiService guis, LanguageService languages, LeDatScheduler scheduler, SoundService sounds, BedrockFormService forms) {
        this.platformPlugin = platformPlugin;
        this.guis = guis;
        this.languages = languages;
        this.scheduler = scheduler;
        this.sounds = sounds == null ? SoundService.disabled() : sounds;
        this.forms = forms == null ? BedrockFormService.disabled() : forms;
        this.nativeBridge = new NativeDialogBridge();
        this.richText = new DialogRichTextRenderer(platformPlugin, MINI);
        this.bedrockText = new BedrockTextRenderer(platformPlugin);
        this.clientDetector = new ViaVersionClientVersionDetector(platformPlugin);
        Bukkit.getPluginManager().registerEvents(this, platformPlugin);
        registerNativeClickEvent();
    }

    @Override
    public PlatformDialog.Builder dialog(Plugin plugin, String dialogId) {
        return PlatformDialog.builder(plugin == null ? platformPlugin : plugin, dialogId)
                .nativeColumns(platformPlugin.getConfig().getInt("dialogs.native.default-columns", 2))
                .nativeButtonWidth(platformPlugin.getConfig().getInt("dialogs.native.default-button-width", 150))
                .fallbackSize(platformPlugin.getConfig().getInt("dialogs.fallback.default-size", 27))
                .fallbackInfoSlot(platformPlugin.getConfig().getInt("dialogs.fallback.info-slot", 13));
    }

    @Override
    public void open(Player player, PlatformDialog dialog) {
        if (player == null || dialog == null) {
            return;
        }
        scheduler.entity(platformPlugin, player, () -> openOnPlayerThread(player, dialog));
    }

    @Override
    public void openTemplate(Player player, Plugin plugin, String templateId, Map<String, String> placeholders, Map<String, Consumer<DialogClick>> callbacks) {
        openTemplate(player, plugin, templateId, placeholders, callbacks, close -> { });
    }

    @Override
    public void openTemplate(Player player, Plugin plugin, String templateId, Map<String, String> placeholders,
                             Map<String, Consumer<DialogClick>> callbacks, Consumer<DialogClose> closeHandler) {
        if (player == null) {
            return;
        }
        Plugin owner = plugin == null ? platformPlugin : plugin;
        Map<String, String> basePlaceholders = basePlaceholders(player, owner, placeholders);
        PlatformDialog built = buildTemplate(owner, templateId, player, basePlaceholders, callbacks == null ? Map.of() : callbacks, closeHandler);
        open(player, built);
    }

    @Override
    public void reloadTemplates(Plugin plugin) {
        if (plugin != null) {
            String ownerKey = plugin.getName().toLowerCase(Locale.ROOT);
            templatesSavedForPlugin.remove(ownerKey);
            invalidateTemplateCache(ownerKey);
            saveBundledTemplates(plugin);
        }
    }

    @Override
    public int templateCount(Plugin plugin) {
        if (plugin == null) {
            return 0;
        }
        int count = 0;
        for (String folder : templateFolders()) {
            File dir = new File(plugin.getDataFolder(), folder);
            File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
            if (files != null) {
                count += files.length;
            }
        }
        return count;
    }

    private PlatformDialog buildTemplate(Plugin owner, String templateId, Player player, Map<String, String> placeholders,
                                         Map<String, Consumer<DialogClick>> callbacks, Consumer<DialogClose> closeHandler) {
        String id = sanitizePath(templateId == null ? "dialog" : templateId).replace('/', '_');
        YamlConfiguration yaml = loadTemplate(owner, id);
        if (yaml == null) {
            owner.getLogger().warning("Missing dialog template: " + id);
            return dialog(owner, id)
                    .title(component("<#ff5555>Missing dialog template", placeholders))
                    .body(component("<gray>Template: <white>" + id, placeholders))
                    .button(DialogButton.cancel(component("<red>Close", placeholders), Component.empty(), click -> close(click.player(), true)))
                    .build();
        }

        int fallbackSize = templateFallbackSize(yaml, platformPlugin.getConfig().getInt("dialogs.fallback.default-size", 27),
                message -> owner.getLogger().warning("Dialog template " + id + ": " + message));
        int defaultButtonWidth = yaml.getInt("native.button-width", platformPlugin.getConfig().getInt("dialogs.native.default-button-width", 150));
        PlatformDialog.Builder builder = dialog(owner, id)
                .title(component(yaml.getString("dialog-title", yaml.getString("title", yaml.getString("name", id))), placeholders))
                .mode(parseMode(yaml.getString("mode", "AUTO")))
                .canCloseWithEscape(yaml.getBoolean("can-close-with-escape", true))
                .nativeColumns(yaml.getInt("native.columns", platformPlugin.getConfig().getInt("dialogs.native.default-columns", 2)))
                .nativeButtonWidth(defaultButtonWidth)
                .fallbackSize(fallbackSize)
                .fallbackInfoSlot(yaml.getInt("fallback.info-slot", platformPlugin.getConfig().getInt("dialogs.fallback.info-slot", 13)))
                .onClose(closeHandler == null ? close -> { } : closeHandler);

        if (yaml.isString("inventory-title")) {
            builder.inventoryTitle(component(InventoryTitleNormalizer.normalize(yaml.getString("inventory-title"), fallbackSize), placeholders));
        }

        for (String line : yaml.getStringList("body")) {
            builder.body(component(line, placeholders));
        }
        ConfigurationSection fallback = yaml.getConfigurationSection("fallback");
        if (fallback != null) {
            ItemStack fill = parseItem(fallback.getConfigurationSection("fill-item"), null, placeholders);
            if (fill != null) builder.fallbackFillItem(fill);
            ItemStack info = parseItem(fallback.getConfigurationSection("info-item"), null, placeholders);
            if (info != null) builder.fallbackInfoItem(info);
        }
        parseInputs(yaml.getConfigurationSection("inputs"), placeholders).forEach(builder::input);
        parseButtons(owner, yaml.getConfigurationSection("buttons"), placeholders, callbacks, defaultButtonWidth).forEach(builder::button);
        return builder.build();
    }

    static int templateFallbackSize(ConfigurationSection yaml, int defaultSize, Consumer<String> warning) {
        int legacySize = yaml.getInt("fallback.size", defaultSize);
        if (yaml.contains("fallback.rows")) {
            if (yaml.isInt("fallback.rows")) {
                int rows = yaml.getInt("fallback.rows");
                if (rows >= 1 && rows <= 6) {
                    return rows * 9;
                }
            }
            warning.accept("fallback.rows must be an integer from 1 to 6; keeping legacy fallback.size");
        }
        return InventoryTitleNormalizer.normalizeSize(legacySize);
    }

    private List<DialogInputSpec> parseInputs(ConfigurationSection root, Map<String, String> placeholders) {
        if (root == null) {
            return List.of();
        }
        List<DialogInputSpec> inputs = new ArrayList<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null || !section.getBoolean("enabled", true)) {
                continue;
            }
            DialogInputType type = parseInputType(section.getString("type", "TEXT"));
            DialogInputSpec.Builder builder = DialogInputSpec.builder(key, type)
                    .label(component(section.getString("label", key), placeholders))
                    .labelVisible(section.getBoolean("label-visible", true))
                    .width(section.getInt("width", 300))
                    .fallbackSlot(section.getInt("fallback-slot", section.getInt("slot", -1)));
            if (section.isString("initial")) builder.initial(replace(section.getString("initial", ""), placeholders, false));
            if (section.isInt("max-length")) builder.maxLength(section.getInt("max-length"));
            if (section.contains("initial-boolean")) builder.initialBoolean(templateBoolean(section, "initial-boolean", placeholders));
            if (section.isString("on-true") || section.isString("on-false")) builder.booleanValues(section.getString("on-true", "true"), section.getString("on-false", "false"));
            if (section.contains("min") || section.contains("max")) builder.range(templateNumber(section, "min", placeholders, 0f), templateNumber(section, "max", placeholders, 100f));
            if (section.contains("initial-number")) builder.initialNumber(templateNumber(section, "initial-number", placeholders, 0f));
            if (section.contains("step")) {
                float step = templateNumber(section, "step", placeholders, 1f);
                if (step <= 0f) throw new IllegalArgumentException("Input step must be positive: " + key);
                builder.step(step);
            }
            if (section.isString("label-format")) builder.labelFormat(section.getString("label-format"));
            ConfigurationSection options = section.getConfigurationSection("options");
            if (options != null) {
                for (String optionKey : options.getKeys(false)) {
                    ConfigurationSection option = options.getConfigurationSection(optionKey);
                    if (option != null) {
                        builder.option(replace(option.getString("value", optionKey), placeholders, false), component(option.getString("label", optionKey), placeholders), option.contains("initial") && templateBoolean(option, "initial", placeholders));
                    }
                }
            }
            ItemStack item = parseItem(section.getConfigurationSection("fallback-item"), null, placeholders);
            if (item != null) builder.fallbackItem(item);
            inputs.add(builder.build());
        }
        return inputs;
    }

    private float templateNumber(ConfigurationSection section, String path, Map<String, String> placeholders, float fallback) {
        Object raw = section.get(path);
        if (raw == null) return fallback;
        float value;
        if (raw instanceof Number number) {
            value = number.floatValue();
        } else if (raw instanceof String text) {
            value = Float.parseFloat(replace(text, placeholders, false).trim());
        } else {
            throw new IllegalArgumentException("Input " + path + " must be numeric");
        }
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Input " + path + " must be finite");
        return value;
    }

    private boolean templateBoolean(ConfigurationSection section, String path, Map<String, String> placeholders) {
        Object raw = section.get(path);
        if (raw instanceof Boolean value) return value;
        if (raw instanceof String text) {
            String value = replace(text, placeholders, false).trim();
            if (value.equalsIgnoreCase("true")) return true;
            if (value.equalsIgnoreCase("false")) return false;
        }
        throw new IllegalArgumentException("Input " + path + " must be boolean");
    }

    private List<DialogButton> parseButtons(Plugin owner, ConfigurationSection root, Map<String, String> placeholders, Map<String, Consumer<DialogClick>> callbacks, int defaultButtonWidth) {
        if (root == null) {
            return List.of(DialogButton.cancel(component("<red>Close", placeholders), Component.empty(), click -> close(click.player(), true)));
        }
        List<DialogButton> buttons = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) {
                continue;
            }
            Component label = component(section.getString("label", section.getString("name", id)), placeholders);
            Component tooltip = component(section.getString("tooltip", ""), placeholders);
            int width = section.getInt("width", defaultButtonWidth);
            int fallbackSlot = section.getInt("fallback-slot", section.getInt("slot", -1));
            boolean closeAfter = section.getBoolean("close-after-click", true);
            ItemStack fallbackItem = parseItem(section.getConfigurationSection("fallback-item"), null, placeholders);
            if (fallbackItem == null) {
                fallbackItem = parseItem(section.getConfigurationSection("item"), defaultButtonItem(id, label, tooltip), placeholders);
            }
            List<String> actions = section.getStringList("actions");
            Consumer<DialogClick> callback = callbacks.get(id.toLowerCase(Locale.ROOT));
            if (callback == null) {
                callback = callbacks.get(id);
            }
            Consumer<DialogClick> action = callback != null ? callback : click -> executeTemplateActions(owner, click, actions, placeholders);
            buttons.add(new DialogButton(id, label, tooltip, width, fallbackSlot, fallbackItem, closeAfter, action));
        }
        return buttons.isEmpty() ? List.of(DialogButton.cancel(component("<red>Close", placeholders), Component.empty(), click -> close(click.player(), true))) : buttons;
    }

    private void executeTemplateActions(Plugin owner, DialogClick click, List<String> actions, Map<String, String> placeholders) {
        if (click == null || click.player() == null || actions == null || actions.isEmpty()) {
            return;
        }
        Map<String, String> merged = new HashMap<>(placeholders);
        if (click.inputs() != null) {
            click.inputs().forEach((key, value) -> {
                String safe = sanitizeCommandValue(value);
                merged.put(key, safe);
                merged.put("input_" + key, safe);
            });
        }
        Player player = click.player();
        for (String raw : actions) {
            String action = replace(raw, merged, false).trim();
            if (action.isBlank()) {
                continue;
            }
            try {
                if (action.startsWith("[player]")) {
                    String command = stripSlash(action.substring("[player]".length()).trim());
                    scheduler.sender(owner, player, () -> player.performCommand(command));
                } else if (action.startsWith("[console]")) {
                    String command = stripSlash(action.substring("[console]".length()).trim());
                    scheduler.sync(owner, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
                } else if (action.startsWith("[message]")) {
                    String message = action.substring("[message]".length()).trim();
                    scheduler.entity(owner, player, () -> player.sendMessage(MINI.deserialize(replace(message, merged, true))));
                } else if (action.startsWith("[dialog]")) {
                    String target = action.substring("[dialog]".length()).trim();
                    openTemplate(player, owner, target, merged, Map.of());
                } else if (action.startsWith("[sound]")) {
                    String soundName = action.substring("[sound]".length()).trim();
                    playSound(owner, player, soundName);
                } else if (action.startsWith("[close]")) {
                    close(player, true);
                } else if (action.startsWith("/")) {
                    scheduler.sender(owner, player, () -> player.performCommand(stripSlash(action)));
                } else {
                    owner.getLogger().warning("Unknown dialog template action: " + action);
                }
            } catch (Throwable error) {
                owner.getLogger().log(Level.WARNING, "Dialog template action failed: " + action, error);
            }
        }
    }

    private void openOnPlayerThread(Player player, PlatformDialog dialog) {
        if (!player.isOnline() || !owner(dialog).isEnabled()) return;
        cleanup(player.getUniqueId());
        forms.cleanup(player.getUniqueId());
        DialogDisplayMode mode = effectiveMode(player, dialog);
        boolean bedrockClient = isBedrockClient(player);
        if (shouldOpenBedrockForm(player, dialog, mode, bedrockClient)) {
            BedrockFormResult result = openBedrockForm(player, dialog);
            if (result.opened()) {
                return;
            }
            if (forms instanceof DefaultBedrockFormService service) {
                service.recordFallbackToDialog();
            }
            boolean inventoryFallbackAllowed = result == BedrockFormResult.UNAVAILABLE
                    && "INVENTORY_FALLBACK".equalsIgnoreCase(platformPlugin.getConfig().getString("forms.dialog-routing.unsupported-input-action", "INVENTORY_FALLBACK"));
            if ((mode == DialogDisplayMode.BEDROCK_FORM_ONLY || bedrockFormsAreStrict()) && !inventoryFallbackAllowed) {
                if (bedrockClient && fallbackEnabled()) {
                    openFallback(player, dialog);
                    return;
                }
                failedOpens.incrementAndGet();
                languages.send(player, platformPlugin, "forms.unavailable", Map.of("reason", result.name().toLowerCase(Locale.ROOT)));
                return;
            }
        }
        if (bedrockClient) {
            if (fallbackEnabled()) {
                openFallback(player, dialog);
            } else {
                failedOpens.incrementAndGet();
                languages.send(player, platformPlugin, "dialog.unavailable", Map.of("reason", "bedrock-native-dialog-blocked"));
            }
            return;
        }
        if (mode == DialogDisplayMode.BEDROCK_FORM_ONLY) {
            failedOpens.incrementAndGet();
            languages.send(player, platformPlugin, "forms.not-bedrock", Map.of());
            return;
        }
        if (!fallbackEnabled() && !nativeDialogsAvailable()) {
            failedOpens.incrementAndGet();
            languages.send(player, platformPlugin, "dialog.unavailable", Map.of("reason", nativeStatus()));
            return;
        }
        if (shouldOpenNative(player, dialog)) {
            try {
                openNative(player, dialog);
                return;
            } catch (Throwable error) {
                nativeFallbacks.incrementAndGet();
                if (mode == DialogDisplayMode.NATIVE_ONLY) {
                    failedOpens.incrementAndGet();
                    platformPlugin.getLogger().log(Level.WARNING, "Native-only dialog failed: " + dialog.dialogId(), error);
                    languages.send(player, platformPlugin, "dialog.unavailable", Map.of("reason", nativeStatus()));
                    return;
                }
                platformPlugin.getLogger().log(Level.WARNING, "Native dialog failed; falling back to inventory GUI: " + dialog.dialogId(), error);
            }
        }
        if (mode == DialogDisplayMode.NATIVE_ONLY) {
            failedOpens.incrementAndGet();
            languages.send(player, platformPlugin, "dialog.unavailable", Map.of("reason", nativeStatus()));
            return;
        }
        if (fallbackEnabled()) {
            openFallback(player, dialog);
        } else {
            failedOpens.incrementAndGet();
            languages.send(player, platformPlugin, "dialog.unavailable", Map.of("reason", nativeStatus()));
        }
    }

    private boolean shouldOpenNative(Player player, PlatformDialog dialog) {
        DialogDisplayMode mode = effectiveMode(player, dialog);
        if (mode == DialogDisplayMode.FALLBACK || mode == DialogDisplayMode.BEDROCK_FORM_ONLY) {
            return false;
        }
        if (!platformPlugin.getConfig().getBoolean("dialogs.native.enabled", true)) {
            return false;
        }
        if (!nativeDialogsAvailable()) {
            return false;
        }
        return canUseNative(player);
    }

    private boolean shouldOpenBedrockForm(Player player, PlatformDialog dialog, DialogDisplayMode mode, boolean bedrockClient) {
        if (player == null || dialog == null) {
            return false;
        }
        if (!platformPlugin.getConfig().getBoolean("forms.dialog-routing.enabled", true)) {
            return false;
        }
        if (mode == DialogDisplayMode.FALLBACK || mode == DialogDisplayMode.NATIVE_ONLY) {
            return false;
        }
        boolean prefer = mode == DialogDisplayMode.BEDROCK_FORM
                || mode == DialogDisplayMode.BEDROCK_FORM_ONLY
                || (platformPlugin.getConfig().getBoolean("forms.prefer-for-bedrock", true)
                && platformPlugin.getConfig().getBoolean("forms.dialog-routing.bedrock-prefer-form", true));
        return prefer && forms.isAvailable() && bedrockClient;
    }

    private boolean bedrockFormsAreStrict() {
        return platformPlugin.getConfig().getBoolean("forms.prefer-for-bedrock", true)
                && platformPlugin.getConfig().getBoolean("forms.dialog-routing.bedrock-prefer-form", true)
                && platformPlugin.getConfig().getBoolean("forms.dialog-routing.enabled", true);
    }

    private BedrockFormResult openBedrockForm(Player player, PlatformDialog dialog) {
        Plugin owner = owner(dialog);
        if (dialog.inputs().isEmpty()) {
            BedrockFormService.SimpleBuilder builder = forms.simple(owner, "dialog:" + dialog.dialogId())
                    .title(dialog.title())
                    .content(dialogContent(dialog))
                    .onClose(close -> safeClose(dialog, player, dialogSessionFromForm(close.session(), dialog, false), "bedrock-close", close.sourceEvent()));
            for (DialogButton button : dialog.buttons()) {
                builder.button(button.id(), button.label(), click -> runDialogButtonFromForm(dialog, button, click));
            }
            return builder.open(player);
        }

        DialogButton submitButton = submitButton(dialog);
        if (submitButton == null) {
            return unsupportedBedrockDialog(player, dialog, "missing-submit-button");
        }
        BedrockFormService.CustomBuilder builder = forms.custom(owner, "dialog:" + dialog.dialogId())
                .title(dialog.title())
                .onClose(close -> safeClose(dialog, player, dialogSessionFromForm(close.session(), dialog, false), "bedrock-close", close.sourceEvent()));
        for (Component line : dialog.body()) {
            builder.label(line);
        }
        for (DialogInputSpec input : dialog.inputs()) {
            switch (input.type()) {
                case TEXT -> builder.input(input.key(), input.label(), "", input.initial());
                case BOOLEAN -> builder.toggle(input.key(), input.label(), input.initialBoolean());
                case SINGLE_OPTION -> builder.dropdown(input.key(), input.label(), input.options().stream()
                        .map(this::bedrockOptionLabel)
                        .toList(), initialOption(input));
                case NUMBER_RANGE -> builder.slider(input.key(), input.label(), input.min(), input.max(),
                        input.step() == null ? 1f : input.step(), input.initialNumber() == null ? input.min() : input.initialNumber());
            }
        }
        builder.submit(submitButton.label(), click -> runDialogButtonFromForm(dialog, submitButton, click));
        return builder.open(player);
    }

    private BedrockFormResult unsupportedBedrockDialog(Player player, PlatformDialog dialog, String reason) {
        owner(dialog).getLogger().warning("Unsupported Bedrock form dialog route: " + dialog.dialogId() + " reason=" + reason);
        String action = platformPlugin.getConfig().getString("forms.dialog-routing.unsupported-input-action", "INVENTORY_FALLBACK");
        if ("MESSAGE_ONLY".equalsIgnoreCase(action)) {
            languages.send(player, platformPlugin, "forms.unsupported-input", Map.of("dialog", dialog.dialogId()));
            return BedrockFormResult.FAILED;
        }
        if ("CLOSE".equalsIgnoreCase(action)) {
            return BedrockFormResult.FAILED;
        }
        return BedrockFormResult.UNAVAILABLE;
    }

    private Component dialogContent(PlatformDialog dialog) {
        if (dialog.body().isEmpty()) {
            return Component.empty();
        }
        Component joined = Component.empty();
        for (int index = 0; index < dialog.body().size(); index++) {
            if (index > 0) {
                joined = joined.append(Component.newline());
            }
            Component line = dialog.body().get(index);
            joined = joined.append(line == null ? Component.empty() : line);
        }
        return joined;
    }

    private DialogButton submitButton(PlatformDialog dialog) {
        DialogButton preferred = dialog.buttons().stream()
                .filter(button -> button.id().equalsIgnoreCase("confirm")
                        || button.id().equalsIgnoreCase("submit")
                        || button.id().equalsIgnoreCase("save")
                        || button.id().equalsIgnoreCase("accept"))
                .findFirst()
                .orElse(null);
        if (preferred != null) return preferred;
        for (DialogButton button : dialog.buttons()) {
            if (!cancelButton(button)) return button;
        }
        return null;
    }

    private String bedrockOptionLabel(DialogInputSpec.Option option) {
        return bedrockText.render(option.label());
    }

    private int initialOption(DialogInputSpec input) {
        for (int i = 0; i < input.options().size(); i++) {
            if (input.options().get(i).initial()) {
                return i;
            }
        }
        return 0;
    }

    private void runDialogButtonFromForm(PlatformDialog dialog, DialogButton button, BedrockFormClick click) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (DialogInputSpec spec : dialog.inputs()) {
            if (spec.type() == DialogInputType.SINGLE_OPTION) {
                Object selected = click.inputs().get(spec.key() + ".index");
                if (!(selected instanceof Integer index) || index < 0 || index >= spec.options().size()) {
                    throw new IllegalArgumentException("Invalid option index: " + spec.key());
                }
                inputs.put(spec.key(), spec.options().get(index).value());
            } else if (click.inputs().containsKey(spec.key())) {
                Object value = click.inputs().get(spec.key());
                inputs.put(spec.key(), value == null ? null : String.valueOf(value));
            }
        }
        inputs = normalizeDialogInputs(dialog, inputs);
        DialogSession session = dialogSessionFromForm(click.session(), dialog, false);
        safeRun(dialog, button, new DialogClick(click.player(), session, button.id(), false, click.sourceEvent(), inputs));
    }

    private static boolean cancelButton(DialogButton button) {
        return switch (button.id()) {
            case "cancel", "close", "back", "no", "deny" -> true;
            default -> false;
        };
    }

    static Map<String, String> normalizeDialogInputs(PlatformDialog dialog, Map<String, String> inputs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (DialogInputSpec spec : dialog.inputs()) {
            String value = inputs.get(spec.key());
            if (value == null || result.containsKey(spec.key())) throw new IllegalArgumentException("Missing or duplicate input: " + spec.key());
            switch (spec.type()) {
                case TEXT -> {
                    if (value.codePointCount(0, value.length()) > spec.maxLength()) throw new IllegalArgumentException("Text input too long: " + spec.key());
                }
                case BOOLEAN -> {
                    if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean: " + spec.key());
                }
                case SINGLE_OPTION -> {
                    boolean found = false;
                    for (DialogInputSpec.Option option : spec.options()) {
                        if (option.value().equals(value)) { found = true; break; }
                    }
                    if (!found) throw new IllegalArgumentException("Invalid option: " + spec.key());
                }
                case NUMBER_RANGE -> {
                    float number = Float.parseFloat(value);
                    if (!Float.isFinite(number) || !Float.isFinite(spec.min()) || !Float.isFinite(spec.max())
                            || number < spec.min() || number > spec.max()) throw new IllegalArgumentException("Invalid number: " + spec.key());
                    if (spec.step() != null) {
                        float step = spec.step();
                        if (!Float.isFinite(step) || step <= 0f) throw new IllegalArgumentException("Invalid numeric step: " + spec.key());
                        double steps = (number - (double) spec.min()) / step;
                        double nearest = spec.min() + Math.rint(steps) * step;
                        double tolerance = Math.max(Math.abs((double) step) * 0.0001D, Math.max(Math.ulp(number), Math.ulp(spec.min())) * 2D);
                        if (number != spec.max() && Math.abs(number - nearest) > tolerance) {
                            throw new IllegalArgumentException("Invalid numeric step: " + spec.key());
                        }
                    }
                }
            }
            result.put(spec.key(), value);
        }
        if (result.size() != inputs.size()) throw new IllegalArgumentException("Unknown dialog input");
        return Map.copyOf(result);
    }

    private DialogSession dialogSessionFromForm(vn.ledat.platform.api.bedrockform.BedrockFormSession formSession, PlatformDialog dialog, boolean nativeDialog) {
        if (formSession == null) {
            return new DialogSession(null, owner(dialog).getName(), dialog.dialogId(), UUID.randomUUID().toString(), nativeDialog, dialog.context());
        }
        return new DialogSession(formSession.playerId(), formSession.pluginName(), dialog.dialogId(), formSession.sessionId(), nativeDialog, dialog.context());
    }

    private DialogDisplayMode effectiveMode(Player player, PlatformDialog dialog) {
        DialogDisplayMode override = displayModeOverride(player == null ? null : player.getUniqueId());
        if (override != null && override != DialogDisplayMode.AUTO) {
            return override;
        }
        return dialog.mode() == null ? DialogDisplayMode.AUTO : dialog.mode();
    }

    private void openNative(Player player, PlatformDialog dialog) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Plugin owner = owner(dialog);
        DialogSession session = new DialogSession(player.getUniqueId(), owner.getName(), dialog.dialogId(), sessionId, true, dialog.context());
        Map<String, String> actionKeys = new LinkedHashMap<>();
        String namespace = sanitizeNamespace(platformPlugin.getName());
        for (DialogButton button : dialog.buttons()) {
            String key = namespace + ":dialogs/" + sanitizePath(owner.getName()) + "/" + dialog.dialogId() + "/" + sessionId + "/" + button.id();
            actionKeys.put(button.id(), key);
            nativeActions.put(key, new PendingAction(dialog, session, button));
        }
        try {
            Object nativeDialog = nativeBridge.createNativeDialog(dialog, actionKeys);
            sessions.put(player.getUniqueId(), session);
            nativeBridge.show(player, nativeDialog);
        } catch (Throwable error) {
            sessions.remove(player.getUniqueId());
            cleanupNativeKeys(session.sessionId());
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(error);
        }
    }

    private void openFallback(Player player, PlatformDialog dialog) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Plugin owner = owner(dialog);
        boolean bedrockClient = isBedrockClient(player);
        DialogSession session = new DialogSession(player.getUniqueId(), owner.getName(), dialog.dialogId(), sessionId, false, dialog.context());
        GuiService.PlatformGui.Builder builder = guis.gui(owner, "dialog:" + dialog.dialogId())
                .title(fallbackTitle(dialog, bedrockClient, bedrockText, platformPlugin.getConfig(), owner.getName()))
                .size(dialog.fallbackSize())
                .fill(fallbackItem(dialog.fallbackFillItem(), bedrockClient))
                .cancelBottomClicks(true)
                .onClose(close -> {
                    DialogSession active = sessions.get(player.getUniqueId());
                    if (active != null && active.sessionId().equals(session.sessionId())) {
                        cleanup(player.getUniqueId());
                        safeClose(dialog, player, session, "inventory-close", close.event());
                    }
                });
        builder.context("dialog.session", session.sessionId());
        Set<Integer> occupied = new HashSet<>();
        if (dialog.fallbackInfoSlot() >= 0 && dialog.fallbackInfoSlot() < dialog.fallbackSize()) {
            builder.button("dialog_info", dialog.fallbackInfoSlot(), fallbackItem(dialog.fallbackInfoItem(), bedrockClient), click -> { });
            occupied.add(dialog.fallbackInfoSlot());
        }
        int[] inputSlots = configuredInputSlots();
        int inputIndex = 0;
        for (DialogInputSpec input : dialog.inputs()) {
            int slot = input.fallbackSlot() >= 0 ? input.fallbackSlot() : nextAvailable(inputSlots, inputIndex++, occupied, dialog.fallbackSize());
            if (slot >= 0 && slot < dialog.fallbackSize()) {
                occupied.add(slot);
                builder.button("input_" + input.key(), slot, fallbackItem(input.fallbackItem(), bedrockClient), click -> languages.send(player, platformPlugin, "dialog.input-fallback", Map.of("input", input.key())));
            }
        }
        int[] configuredSlots = configuredActionSlots();
        int index = 0;
        for (DialogButton button : dialog.buttons()) {
            int slot = button.fallbackSlot() >= 0 ? button.fallbackSlot() : nextAvailable(configuredSlots, index++, occupied, dialog.fallbackSize());
            if (slot >= 0 && slot < dialog.fallbackSize()) {
                occupied.add(slot);
                builder.button(button.id(), slot, fallbackItem(button.fallbackItem(), bedrockClient), click -> handleFallbackButton(dialog, session, button, click));
            }
        }
        sessions.put(player.getUniqueId(), session);
        guis.open(player, builder.build());
    }

    static Component fallbackTitle(PlatformDialog dialog, boolean bedrockClient, BedrockTextRenderer renderer,
                                   ConfigurationSection config, String ownerName) {
        if (bedrockClient) {
            // Preserve the existing Bedrock text projection and MiniMessage fallback behavior.
            return parseFallbackTitle(renderer.render(dialog.title()));
        }
        String override = inventoryTitleOverride(config, ownerName, dialog.dialogId());
        if (override == null) {
            return dialog.inventoryTitle();
        }
        String raw = InventoryTitleNormalizer.normalize(override.replace("{title}", MINI.serialize(dialog.title())), dialog.fallbackSize());
        return parseFallbackTitle(raw);
    }

    private static Component parseFallbackTitle(String raw) {
        try {
            return MINI.deserialize(raw);
        } catch (Exception ignored) {
            return Component.text(raw);
        }
    }

    static String inventoryTitleOverride(ConfigurationSection config, String ownerName, String dialogId) {
        ConfigurationSection owners = config.getConfigurationSection("dialogs.fallback.inventory-titles");
        if (owners == null || ownerName == null || dialogId == null) {
            return null;
        }
        // Bukkit expands dotted YAML keys; flattened maps reconstruct the exact configured IDs.
        // Lookup remains owner-scoped, with no wildcard or global defaults.
        for (Map.Entry<String, Object> entry : owners.getValues(true).entrySet()) {
            if (entry.getKey().equalsIgnoreCase(ownerName) && entry.getValue() instanceof ConfigurationSection dialogs) {
                Object value = dialogs.getValues(true).get(dialogId);
                return value instanceof String title ? title : null;
            }
        }
        return null;
    }

    private ItemStack fallbackItem(ItemStack item, boolean bedrockClient) {
        return fallbackItem(item, bedrockClient, bedrockText);
    }

    static ItemStack fallbackItem(ItemStack item, boolean bedrockClient, BedrockTextRenderer renderer) {
        return bedrockClient ? BedrockItemTextProjection.project(item, renderer) : item;
    }

    private int nextAvailable(int[] slots, int startIndex, Set<Integer> occupied, int size) {
        for (int i = Math.max(0, startIndex); i < slots.length; i++) {
            int slot = slots[i];
            if (slot >= 0 && slot < size && !occupied.contains(slot)) {
                return slot;
            }
        }
        for (int slot = 0; slot < size; slot++) {
            if (!occupied.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private void handleFallbackButton(PlatformDialog dialog, DialogSession session, DialogButton button, GuiClick guiClick) {
        Player player = guiClick.player();
        if (!dialog.inputs().isEmpty() && !cancelButton(button)) {
            languages.send(player, platformPlugin, "dialog.input-fallback", Map.of("input", dialog.inputs().get(0).key()));
            return;
        }
        if (button.closeAfterClick()) {
            cleanup(player.getUniqueId());
            guis.cleanup(player.getUniqueId());
            guis.close(player, false);
        }
        safeRun(dialog, button, new DialogClick(player, session, button.id(), false, guiClick.event()));
    }

    private void registerNativeClickEvent() {
        if (!nativeBridge.available() || nativeBridge.customClickEventClass() == null) {
            return;
        }
        EventExecutor executor = (listener, event) -> handleNativeEvent(event);
        Bukkit.getPluginManager().registerEvent(nativeBridge.customClickEventClass(), this, EventPriority.NORMAL, executor, platformPlugin, false);
    }

    private void handleNativeEvent(Event event) {
        String key = nativeBridge.eventIdentifier(event);
        PendingAction pending = nativeActions.get(key);
        if (pending == null) {
            return;
        }
        Player player = nativeBridge.eventPlayer(event);
        if (player == null || !player.getUniqueId().equals(pending.session().playerId())) {
            return;
        }
        Map<String, String> inputs = nativeBridge.eventInputs(event, pending.dialog().inputs());
        scheduler.entity(owner(pending.dialog()), player, () -> {
            if (!player.isOnline() || !owner(pending.dialog()).isEnabled()
                    || sessions.get(player.getUniqueId()) != pending.session()) return;
            Map<String, String> normalized;
            try {
                normalized = cancelButton(pending.button()) ? Map.of() : normalizeDialogInputs(pending.dialog(), inputs);
            } catch (IllegalArgumentException invalid) {
                if (nativeActions.remove(key, pending) && sessions.remove(player.getUniqueId(), pending.session())) {
                    cleanupNativeKeys(pending.session().sessionId());
                    languages.send(player, platformPlugin, "forms.invalid", Map.of());
                }
                return;
            }
            if (!nativeActions.remove(key, pending) || !sessions.remove(player.getUniqueId(), pending.session())) return;
            cleanupNativeKeys(pending.session().sessionId());
            safeRun(pending.dialog(), pending.button(), new DialogClick(player, pending.session(), pending.button().id(), true, event, normalized));
        });
    }

    private void cleanupNativeKeys(String sessionId) {
        nativeActions.entrySet().removeIf(entry -> entry.getValue().session().sessionId().equals(sessionId));
    }

    private void safeRun(PlatformDialog dialog, DialogButton button, DialogClick click) {
        try {
            if (click.nativeDialog()) {
                playDialogCue(owner(dialog), click.player(), "dialog-click", Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.75F, 1.0F, 70L);
            }
            button.action().accept(click);
        } catch (Throwable error) {
            owner(dialog).getLogger().log(Level.WARNING, "Dialog action failed: " + dialog.dialogId() + "/" + button.id(), error);
            languages.send(click.player(), owner(dialog), "dialog.error", Map.of("dialog", dialog.dialogId(), "action", button.id()));
            playDialogCue(owner(dialog), click.player(), "dialog-error", Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.7F, 1.0F, 150L);
        }
    }

    private void safeClose(PlatformDialog dialog, Player player, DialogSession session, String reason, Object sourceEvent) {
        try {
            dialog.closeHandler().accept(new DialogClose(player, session, session.nativeDialog(), reason, sourceEvent));
        } catch (Throwable error) {
            owner(dialog).getLogger().log(Level.WARNING, "Dialog close handler failed: " + dialog.dialogId(), error);
        }
    }

    @Override
    public void close(Player player, boolean clearSession) {
        if (player == null) {
            return;
        }
        DialogSession active = sessions.get(player.getUniqueId());
        if (clearSession) {
            cleanup(player.getUniqueId());
        }
        scheduler.entity(platformPlugin, player, () -> {
            if (active != null && active.nativeDialog() && nativeDialogsAvailable()) {
                nativeBridge.close(player);
            } else {
                guis.close(player, clearSession);
            }
        });
    }

    private boolean isBedrockClient(Player player) {
        if (player == null) {
            return false;
        }
        // A connection's client type never changes mid-session, while the raw
        // detection below costs plugin-list scans plus reflection. Cache per
        // player; entries are dropped on quit and on platform reload.
        Boolean cached = bedrockClientCache.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        boolean bedrock = detectBedrockClient(player);
        bedrockClientCache.put(player.getUniqueId(), bedrock);
        return bedrock;
    }

    private boolean detectBedrockClient(Player player) {
        try {
            if (forms.isBedrock(player)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (isFloodgateBedrockPlayer(player)) {
            return true;
        }
        return isGeyserBedrockPlayer(player);
    }

    private boolean isFloodgateBedrockPlayer(Player player) {
        Plugin floodgate = findEnabledPlugin("floodgate", "Floodgate");
        if (player == null || floodgate == null) {
            return false;
        }
        try {
            ClassLoader loader = floodgate.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", false, loader);
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return false;
            }
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, player.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isGeyserBedrockPlayer(Player player) {
        Plugin geyser = findEnabledPlugin("Geyser", "Geyser-Spigot", "Geyser-Bukkit");
        if (player == null || geyser == null || !geyser.isEnabled()) {
            return false;
        }
        try {
            ClassLoader loader = geyser.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi", false, loader);
            Object api = apiClass.getMethod("api").invoke(null);
            if (api == null) {
                return false;
            }
            try {
                Method method = apiClass.getMethod("isBedrockPlayer", UUID.class);
                Object result = method.invoke(api, player.getUniqueId());
                if (result instanceof Boolean bool) {
                    return bool;
                }
            } catch (NoSuchMethodException ignored) {
            }
            Method method = apiClass.getMethod("connectionByUuid", UUID.class);
            return method.invoke(api, player.getUniqueId()) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Plugin findEnabledPlugin(String... names) {
        for (String name : names) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null && plugin.isEnabled()) {
                return plugin;
            }
        }
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            for (String name : names) {
                if (plugin.isEnabled() && plugin.getName().equalsIgnoreCase(name)) {
                    return plugin;
                }
            }
        }
        return null;
    }

    @Override public boolean nativeDialogsAvailable() { return platformPlugin.getConfig().getBoolean("dialogs.enabled", true) && platformPlugin.getConfig().getBoolean("dialogs.native.enabled", true) && nativeBridge.available(); }
    @Override public boolean fallbackEnabled() { return platformPlugin.getConfig().getBoolean("dialogs.enabled", true) && platformPlugin.getConfig().getBoolean("dialogs.fallback.enabled", true); }
    @Override public boolean canUseNative(Player player) { return nativeDialogsAvailable() && !isBedrockClient(player) && clientDetector.compatible(player); }
    @Override public boolean canOpen(Player player) { return fallbackEnabled() || canUseNative(player) || forms.canOpen(player); }
    @Override public String nativeStatus() { return nativeBridge.status(); }
    @Override public String clientStatus(Player player) { return clientDetector.status(player); }
    public String clientDetectorStatus() { return clientDetector.detectorStatus(); }

    public void reload() {
        clientDetector.reload();
        templateCache.clear();
        // Bedrock bridges may have been (un)hooked by the reload; re-detect lazily.
        bedrockClientCache.clear();
    }

    @Override
    public void setDisplayModeOverride(UUID playerId, DialogDisplayMode mode) {
        if (playerId == null) {
            return;
        }
        if (mode == null || mode == DialogDisplayMode.AUTO) {
            displayModeOverrides.remove(playerId);
            return;
        }
        displayModeOverrides.put(playerId, mode);
    }

    @Override
    public DialogDisplayMode displayModeOverride(UUID playerId) {
        return playerId == null ? null : displayModeOverrides.get(playerId);
    }

    @Override
    public Map<UUID, DialogDisplayMode> displayModeOverrides() {
        return Map.copyOf(displayModeOverrides);
    }

    @Override
    public Map<String, String> diagnostics(Player player) {
        DialogDisplayMode override = displayModeOverride(player == null ? null : player.getUniqueId());
        Map<String, String> out = new LinkedHashMap<>();
        out.put("native", String.valueOf(nativeDialogsAvailable()));
        out.put("fallback", String.valueOf(fallbackEnabled()));
        out.put("client", player == null ? "unknown" : clientStatus(player));
        out.put("status", nativeStatus());
        out.put("display-override", override == null ? "AUTO" : override.name());
        out.put("bedrock-form", String.valueOf(player != null && forms.canOpen(player)));
        out.put("bedrock-client", String.valueOf(player != null && isBedrockClient(player)));
        BedrockTextRenderer.ProjectionStats projection = bedrockText.stats();
        out.put("bedrock-text-object-fallbacks", String.valueOf(projection.objectFallbacks()));
        out.put("bedrock-text-projection-failures", String.valueOf(projection.projectionFailures()));
        return out;
    }

    @Override public DialogSession session(UUID playerId) { return playerId == null ? null : sessions.get(playerId); }
    @Override public int sessionCount() { return sessions.size(); }
    @Override public int sessionCount(Plugin plugin) {
        if (plugin == null) return sessionCount();
        String name = plugin.getName();
        return (int) sessions.values().stream().filter(session -> session.pluginName().equalsIgnoreCase(name)).count();
    }
    @Override public DialogStats stats() {
        return new DialogStats(
                sessions.size(),
                nativeActions.size(),
                displayModeOverrides.size(),
                templatesSavedForPlugin.size(),
                expiredSessionCleanups.get(),
                nativeFallbacks.get(),
                failedOpens.get()
        );
    }
    @Override public void cleanup(UUID playerId) {
        if (playerId == null) return;
        DialogSession removed = sessions.remove(playerId);
        if (removed != null) cleanupNativeKeys(removed.sessionId());
    }
    @Override public void cleanup(Plugin plugin) {
        if (plugin == null) return;
        String name = plugin.getName();
        List<UUID> remove = new ArrayList<>();
        sessions.forEach((uuid, session) -> { if (session.pluginName().equalsIgnoreCase(name)) remove.add(uuid); });
        remove.forEach(this::cleanup);
        String ownerKey = name.toLowerCase(Locale.ROOT);
        templatesSavedForPlugin.remove(ownerKey);
        invalidateTemplateCache(ownerKey);
    }
    @Override public int cleanupExpired() {
        long ttlSeconds = Math.max(30L, platformPlugin.getConfig().getLong("dialogs.session-ttl-seconds", 300L));
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(ttlSeconds));
        List<UUID> remove = sessions.entrySet().stream().filter(entry -> entry.getValue().createdAt().isBefore(cutoff)).map(Map.Entry::getKey).toList();
        remove.forEach(this::cleanup);
        if (!remove.isEmpty()) {
            expiredSessionCleanups.addAndGet(remove.size());
        }
        nativeActions.entrySet().removeIf(entry -> entry.getValue().session().createdAt().isBefore(cutoff));
        return remove.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        cleanup(playerId);
        clearDisplayModeOverride(playerId);
        bedrockClientCache.remove(playerId);
    }

    private YamlConfiguration loadTemplate(Plugin owner, String id) {
        ensureBundledTemplatesSaved(owner);
        String ownerKey = owner.getName().toLowerCase(Locale.ROOT);
        for (String folder : templateFolders()) {
            File file = new File(new File(owner.getDataFolder(), folder), id + ".yml");
            if (file.exists()) {
                return cachedFileTemplate(ownerKey, file);
            }
            YamlConfiguration bundled = cachedBundledTemplate(owner, ownerKey, folder + "/" + id + ".yml");
            if (bundled != null) {
                return bundled;
            }
        }
        return null;
    }

    /**
     * Templates are parsed on the thread that opens the dialog (usually a command
     * or GUI click on the player thread), so re-reading YAML per open is avoidable
     * latency. Disk templates are cached per file and revalidated by lastModified;
     * bundled jar templates are immutable for the plugin's lifetime.
     */
    private YamlConfiguration cachedFileTemplate(String ownerKey, File file) {
        String key = "file:" + ownerKey + ":" + file.getAbsolutePath();
        long lastModified = file.lastModified();
        CachedTemplate cached = templateCache.get(key);
        if (cached != null && cached.lastModified() == lastModified) {
            return cached.yaml();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        templateCache.put(key, new CachedTemplate(lastModified, yaml));
        return yaml;
    }

    private YamlConfiguration cachedBundledTemplate(Plugin owner, String ownerKey, String path) {
        String key = "jar:" + ownerKey + ":" + path;
        CachedTemplate cached = templateCache.get(key);
        if (cached != null) {
            return cached.yaml();
        }
        YamlConfiguration bundled = loadBundledTemplate(owner, path);
        // Jar contents cannot change while the plugin is loaded, so misses are
        // cached as well; reloadTemplates/cleanup clear the plugin's entries.
        templateCache.put(key, new CachedTemplate(-1L, bundled));
        return bundled;
    }

    private void invalidateTemplateCache(String ownerKey) {
        String filePrefix = "file:" + ownerKey + ":";
        String jarPrefix = "jar:" + ownerKey + ":";
        templateCache.keySet().removeIf(key -> key.startsWith(filePrefix) || key.startsWith(jarPrefix));
    }

    private YamlConfiguration loadBundledTemplate(Plugin owner, String path) {
        if (owner == null || path == null || path.isBlank()) {
            return null;
        }
        try (var input = owner.getResource(path)) {
            if (input == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable error) {
            owner.getLogger().log(Level.WARNING, "Could not load bundled dialog template " + path, error);
            return null;
        }
    }

    private void ensureBundledTemplatesSaved(Plugin owner) {
        if (owner == null) {
            return;
        }
        String key = owner.getName().toLowerCase(Locale.ROOT);
        if (!templatesSavedForPlugin.add(key)) {
            return;
        }
        saveBundledTemplates(owner);
    }

    private void saveBundledTemplates(Plugin owner) {
        if (!platformPlugin.getConfig().getBoolean("dialogs.templates.auto-save-bundled", true)) {
            return;
        }
        Set<String> paths = discoverBundledTemplatePaths(owner);
        // Backward-compatible safety list for legacy plugins where resource discovery
        // is not available because of a custom class loader or exploded dev layout.
        for (String folder : templateFolders()) {
            paths.add(folder + "/settings_main.yml");
            paths.add(folder + "/settings_general.yml");
            paths.add(folder + "/settings_privacy.yml");
            paths.add(folder + "/settings_chat.yml");
            paths.add(folder + "/settings_notifications.yml");
            paths.add(folder + "/settings_pvp.yml");
            paths.add(folder + "/settings_visuals.yml");
            paths.add(folder + "/settings_scoreboard.yml");
            paths.add(folder + "/leaderboards.yml");
            paths.add(folder + "/stats.yml");
            paths.add(folder + "/cosmetics_main.yml");
            paths.add(folder + "/cosmetics_messages.yml");
            paths.add(folder + "/cosmetics_effects.yml");
            paths.add(folder + "/cosmetics_chat.yml");
            paths.add(folder + "/cosmetics_tags.yml");
            paths.add(folder + "/cosmetics_pets.yml");
            paths.add(folder + "/cosmetics_purchase_confirm.yml");
            paths.add(folder + "/teams_main.yml");
            paths.add(folder + "/teams_disband_confirm.yml");
            paths.add(folder + "/teams_language.yml");
            paths.add(folder + "/duels_main.yml");
            paths.add(folder + "/duels_outgoing_invite.yml");
            paths.add(folder + "/duels_incoming_invite.yml");
            paths.add(folder + "/duels_leaderboard.yml");
            paths.add(folder + "/duels_settings.yml");
            paths.add(folder + "/duels_spectator.yml");
            paths.add(folder + "/duels_history.yml");
        }
        paths.forEach(path -> trySave(owner, path));
    }

    private Set<String> discoverBundledTemplatePaths(Plugin owner) {
        Set<String> paths = new LinkedHashSet<>();
        if (owner == null) {
            return paths;
        }
        Set<String> folders = new LinkedHashSet<>(templateFolders());
        try {
            URI uri = owner.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            File source = new File(uri);
            if (source.isFile()) {
                try (ZipFile zip = new ZipFile(source)) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        String name = entry.getName().replace('\\', '/');
                        if (isBundledTemplatePath(name, folders)) {
                            paths.add(name);
                        }
                    }
                }
            } else if (source.isDirectory()) {
                java.nio.file.Path root = source.toPath();
                try (var stream = java.nio.file.Files.walk(root, 4)) {
                    stream.filter(java.nio.file.Files::isRegularFile).forEach(path -> {
                        String rel = root.relativize(path).toString().replace('\\', '/');
                        if (isBundledTemplatePath(rel, folders)) {
                            paths.add(rel);
                        }
                    });
                }
            }
        } catch (Throwable ignored) {
            // Discovery is best-effort; trySave safety list still covers legacy templates.
        }
        return paths;
    }

    private boolean isBundledTemplatePath(String path, Set<String> folders) {
        if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".yml")) {
            return false;
        }
        for (String folder : folders) {
            String prefix = folder.endsWith("/") ? folder : folder + "/";
            if (path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0) {
                return true;
            }
        }
        return false;
    }

    private void trySave(Plugin owner, String path) {
        if (!(owner instanceof JavaPlugin javaPlugin)) {
            return;
        }
        File file = new File(javaPlugin.getDataFolder(), path);
        if (file.exists()) return;
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            javaPlugin.saveResource(path, false);
        } catch (IllegalArgumentException ignored) {
            // Plugin does not bundle this optional template.
        } catch (Throwable error) {
            owner.getLogger().log(Level.WARNING, "Could not save dialog template " + path, error);
        }
    }

    private List<String> templateFolders() {
        List<String> folders = platformPlugin.getConfig().getStringList("dialogs.templates.folders");
        return folders.isEmpty() ? List.of("dialogs", "inventories") : folders;
    }

    private int[] configuredActionSlots() {
        List<Integer> raw = platformPlugin.getConfig().getIntegerList("dialogs.fallback.action-slots");
        if (raw.isEmpty()) raw = List.of(10, 11, 12, 14, 15, 16, 20, 22, 24);
        return raw.stream().filter(slot -> slot >= 0 && slot <= 53).mapToInt(Integer::intValue).toArray();
    }

    private int[] configuredInputSlots() {
        List<Integer> raw = platformPlugin.getConfig().getIntegerList("dialogs.fallback.input-slots");
        if (raw.isEmpty()) raw = List.of(4, 13, 22);
        return raw.stream().filter(slot -> slot >= 0 && slot <= 53).mapToInt(Integer::intValue).toArray();
    }

    private ItemStack parseItem(ConfigurationSection section, ItemStack fallback, Map<String, String> placeholders) {
        if (section == null) return fallback == null ? null : fallback.clone();
        String materialName = section.getString("material", section.isString("url") ? "PLAYER_HEAD" : (fallback == null ? "PAPER" : fallback.getType().name()));
        Material material = material(replace(materialName, placeholders, false));
        ItemStack item = new ItemStack(material);
        item.setAmount(Math.max(1, Math.min(64, section.getInt("amount", fallback == null ? 1 : fallback.getAmount()))));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = section.getString("name", section.getString("display-name", section.getString("displayname", null)));
            if (name != null) meta.displayName(nonItalic(component(name, placeholders)));
            List<String> loreRaw = section.getStringList("lore");
            if (!loreRaw.isEmpty()) meta.lore(loreRaw.stream().map(line -> nonItalic(component(line, placeholders))).toList());
            if (section.isInt("custom-model-data")) meta.setCustomModelData(section.getInt("custom-model-data"));
            applyHeadOwner(meta, section, placeholders);
            if (section.getBoolean("glow", false)) {
                item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
        }
        return GuiService.nonItalicItem(item);
    }

    private void applyHeadOwner(ItemMeta meta, ConfigurationSection section, Map<String, String> placeholders) {
        if (!(meta instanceof SkullMeta skull)) {
            return;
        }
        String owner = section.getString("skull-owner", section.getString("head-owner", section.getString("owner", "")));
        owner = replace(owner, placeholders, false).trim();
        if (owner.isBlank()) {
            return;
        }
        try {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
        } catch (Throwable error) {
            platformPlugin.getLogger().fine("Could not apply dialog fallback head owner: " + owner);
        }
    }

    private ItemStack defaultButtonItem(String id, Component label, Component tooltip) {
        Material material = switch (sanitizePath(id)) {
            case "confirm", "yes", "accept", "send", "send_tpa", "send_tpahere" -> Material.LIME_CONCRETE;
            case "cancel", "no", "deny", "back", "close" -> Material.RED_CONCRETE;
            case "stats", "leaderboard", "leaderboards" -> Material.GOLD_INGOT;
            case "settings", "general" -> Material.COMPARATOR;
            default -> Material.PAPER;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(nonItalic(label));
            if (tooltip != null && !tooltip.equals(Component.empty())) meta.lore(List.of(nonItalic(tooltip)));
            item.setItemMeta(meta);
        }
        return GuiService.nonItalicItem(item);
    }

    @Override
    public Component renderText(String mini, Map<String, String> placeholders) {
        return component(mini, placeholders);
    }

    @Override
    public Component itemSprite(String materialName) {
        return richText.itemSprite(materialName);
    }

    @Override
    public Component playerHead(String playerNameOrUuid) {
        return richText.playerHead(playerNameOrUuid);
    }

    @Override
    public boolean richObjectComponentsAvailable() {
        return richText.available();
    }

    public String richObjectStatus() {
        return richText.status();
    }

    private Component component(String mini, Map<String, String> placeholders) {
        return richText.render(mini, placeholders);
    }

    private Component nonItalic(Component component) {
        return GuiService.nonItalic(component);
    }

    private String replace(String text, Map<String, String> placeholders, boolean escapeMini) {
        String result = text == null ? "" : text;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue();
                result = result.replace("{" + entry.getKey() + "}", escapeMini ? MINI.escapeTags(value) : value);
            }
        }
        return result;
    }

    private Map<String, String> basePlaceholders(Player player, Plugin owner, Map<String, String> extra) {
        Map<String, String> map = new HashMap<>();
        map.put("player", player == null ? "" : player.getName());
        map.put("uuid", player == null ? "" : player.getUniqueId().toString());
        map.put("plugin", owner == null ? platformPlugin.getName() : owner.getName());
        if (extra != null) map.putAll(extra);
        return map;
    }

    private String sanitizeCommandValue(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String stripSlash(String command) {
        return command == null ? "" : command.replaceFirst("^/+", "");
    }

    private void playSound(Plugin owner, Player player, String soundName) {
        if (player == null) {
            return;
        }
        Plugin safeOwner = owner == null ? platformPlugin : owner;
        String raw = soundName == null ? "" : soundName.trim();
        if (raw.isBlank() || raw.equalsIgnoreCase("none")) {
            return;
        }
        String cueKey = normalizeCueKey(raw);
        if (cueKey.isBlank()) {
            return;
        }

        long cooldownMillis = platformPlugin.getConfig().getLong("dialogs.actions.sound.cooldown-ms", 80L);
        SoundCue fallback = sounds.cue(safeOwner, "dialogs.actions.sound." + cueKey, raw, SoundCue.disabled())
                .withCooldownMillis(cooldownMillis);
        boolean played = sounds.playCue(safeOwner, player, cueKey, fallback);
        boolean rawSoundFallback = fallback.enabled() && fallback.sound() != null;
        if (!played && !rawSoundFallback && !safeOwner.getName().equalsIgnoreCase(platformPlugin.getName())) {
            played = sounds.playCue(platformPlugin, player, cueKey, fallback);
        }
        if (!played && !rawSoundFallback
                && platformPlugin.getConfig().getBoolean("dialogs.actions.sound.warn-missing", true)) {
            safeOwner.getLogger().warning("Unknown dialog sound/cue: " + raw + " (tried sounds.cues." + cueKey + ").");
        }
    }

    private void playDialogCue(Plugin owner, Player player, String cueKey, Sound sound, SoundCategory category,
                               float volume, float pitch, long cooldownMillis) {
        sounds.playCue(owner == null ? platformPlugin : owner, player, cueKey,
                SoundCue.of(sound, category, volume, pitch).withCooldownMillis(cooldownMillis));
    }

    private String normalizeCueKey(String value) {
        return value == null ? "" : value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace('.', '-')
                .replace('_', '-')
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private Material material(String raw) {
        Material material = raw == null ? null : Material.matchMaterial(raw);
        return material == null ? Material.PAPER : material;
    }

    private DialogDisplayMode parseMode(String raw) {
        try { return DialogDisplayMode.valueOf((raw == null ? "AUTO" : raw).toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return DialogDisplayMode.AUTO; }
    }

    private DialogInputType parseInputType(String raw) {
        try { return DialogInputType.valueOf((raw == null ? "TEXT" : raw).toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (Exception ignored) { return DialogInputType.TEXT; }
    }

    private Plugin owner(PlatformDialog dialog) {
        return dialog == null || dialog.plugin() == null ? platformPlugin : dialog.plugin();
    }

    private static String sanitizeNamespace(String value) {
        String sanitized = sanitizePath(value).replace('/', '_');
        return sanitized.isBlank() ? "ledat" : sanitized;
    }

    private static String sanitizePath(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }

    private record PendingAction(PlatformDialog dialog, DialogSession session, DialogButton button) { }

    private record CachedTemplate(long lastModified, YamlConfiguration yaml) { }
}
