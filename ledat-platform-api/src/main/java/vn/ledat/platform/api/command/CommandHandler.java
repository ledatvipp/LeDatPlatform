package vn.ledat.platform.api.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Convenience handler for LeDat child plugins migrating away from Bukkit
 * CommandExecutor/TabCompleter while keeping the familiar sender/label/args
 * shape internally.
 *
 * <p>Implementations are still registered through {@link CommandService}; the
 * platform converts {@link CommandContext} into this small handler contract and
 * exposes the command through Paper Brigadier.</p>
 */
public interface CommandHandler extends CommandExecutor, CommandTabProvider {
    boolean onCommand(CommandSender sender, String label, String[] args);

    default List<String> onTabComplete(CommandSender sender, String label, String[] args) {
        return List.of();
    }

    @Override
    default void execute(CommandContext context) {
        onCommand(context.sender(), context.label(), context.args());
    }

    @Override
    default List<String> complete(CommandContext context) {
        List<String> completions = onTabComplete(context.sender(), context.label(), context.args());
        return completions == null ? List.of() : completions;
    }
}
