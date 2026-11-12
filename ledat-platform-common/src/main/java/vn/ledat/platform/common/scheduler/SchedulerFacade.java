package vn.ledat.platform.common.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Shared scheduling contract for LeDat plugins.
 *
 * Rules:
 * - async: database/file/network/heavy work only;
 * - global: server/global-safe work only;
 * - entity: player/entity Bukkit API;
 * - location: region/block/world location work;
 * - never keep Player objects across async work unless re-scheduled via entity scheduler.
 */
public interface SchedulerFacade {
    ScheduledTaskHandle runAsync(Runnable task);

    ScheduledTaskHandle runAsyncLater(Runnable task, long delayTicks);

    ScheduledTaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks);

    ScheduledTaskHandle runGlobal(Runnable task);

    ScheduledTaskHandle runGlobalLater(Runnable task, long delayTicks);

    ScheduledTaskHandle runGlobalRepeating(Runnable task, long delayTicks, long periodTicks);

    ScheduledTaskHandle runEntity(Entity entity, Runnable task);

    ScheduledTaskHandle runEntityLater(Entity entity, Runnable task, long delayTicks);

    ScheduledTaskHandle runEntityRepeating(Entity entity, Runnable task, long delayTicks, long periodTicks);

    ScheduledTaskHandle runAtLocation(Location location, Runnable task);

    ScheduledTaskHandle runAtLocationLater(Location location, Runnable task, long delayTicks);

    ScheduledTaskHandle runAtLocationRepeating(Location location, Runnable task, long delayTicks, long periodTicks);

    CompletableFuture<Boolean> teleportAsync(Entity entity, Location location);

    default ScheduledTaskHandle runForSender(CommandSender sender, Runnable task) {
        if (sender instanceof Entity entity) {
            return runEntity(entity, task);
        }
        return runGlobal(task);
    }

    default ScheduledTaskHandle runForSenderLater(CommandSender sender, Runnable task, long delayTicks) {
        if (sender instanceof Entity entity) {
            return runEntityLater(entity, task, delayTicks);
        }
        return runGlobalLater(task, delayTicks);
    }

    default ScheduledTaskHandle runPlayer(UUID playerId, Consumer<Player> task) {
        if (playerId == null || task == null) {
            return ScheduledTaskHandle.NOOP;
        }
        return runGlobal(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            runEntity(player, () -> {
                if (player.isOnline()) {
                    task.accept(player);
                }
            });
        });
    }

    default ScheduledTaskHandle runPlayerLater(UUID playerId, Consumer<Player> task, long delayTicks) {
        if (playerId == null || task == null) {
            return ScheduledTaskHandle.NOOP;
        }
        return runGlobalLater(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            runEntityLater(player, () -> {
                if (player.isOnline()) {
                    task.accept(player);
                }
            }, delayTicks);
        }, 1L);
    }

    void cancelAll();
}
