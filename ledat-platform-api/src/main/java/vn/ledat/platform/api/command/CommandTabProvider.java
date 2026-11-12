package vn.ledat.platform.api.command;

import java.util.List;

@FunctionalInterface
public interface CommandTabProvider {
    List<String> complete(CommandContext context);
}
