package vn.ledat.platform.api.command;

@FunctionalInterface
public interface CommandExecutor {
    void execute(CommandContext context) throws Exception;
}
