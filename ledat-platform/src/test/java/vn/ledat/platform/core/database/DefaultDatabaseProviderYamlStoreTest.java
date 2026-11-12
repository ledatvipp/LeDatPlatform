package vn.ledat.platform.core.database;

import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.ledat.platform.api.database.DatabaseProvider;
import vn.ledat.platform.api.database.StorageMode;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultDatabaseProviderYamlStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void retainedYamlStoreHandleRemainsTrackedAndWritableAfterReload() throws Exception {
        Path dataFolder = tempDir.resolve("reload-yaml");
        Plugin plugin = plugin(dataFolder);
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);

        try {
            DatabaseProvider.YamlStore retained = provider.yamlStore(plugin, "reload-safe").orElseThrow();
            retained.set("before", "one").join();
            provider.reload();

            DatabaseProvider.YamlStore reacquired = provider.yamlStore(plugin, "reload-safe").orElseThrow();
            assertSame(retained, reacquired, "reload must preserve the public YAML store handle");
            retained.set("after", "two").join();
            provider.flushAll().get(5, TimeUnit.SECONDS);

            YamlConfiguration loaded = YamlConfiguration.loadConfiguration(retained.file());
            assertEquals("one", loaded.getString("before"));
            assertEquals("two", loaded.getString("after"));
            assertTrue(provider.storageUnits().values().stream()
                    .anyMatch(unit -> unit.path().equals(retained.file().getPath())),
                    "retained handle must remain visible to health/flush tracking");
        } finally {
            provider.close();
        }
    }

    @Test
    void stressConcurrentYamlWriteBehindAndHealthFlushesDoNotDropWrites() throws Exception {
        Path dataFolder = tempDir.resolve("stress-yaml");
        Plugin plugin = plugin(dataFolder, config -> {
            config.set("storage.executor.core-threads", 4);
            config.set("storage.executor.max-threads", 16);
            config.set("storage.executor.queue-capacity", 8192);
            config.set("storage.executor.scale-before-queue", true);
            config.set("storage.executor.prestart-core-threads", false);
            config.set("storage.executor.prestart-local-mode", false);
            config.set("storage.yaml.health-flush-max-stores", 8);
            config.set("storage.executor.shutdown-timeout-ms", 10_000L);
        });
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
        int storeCount = 64;
        int writerCount = 16;
        int writesPerWriter = 400;
        List<DatabaseProvider.YamlStore> stores = new ArrayList<>(storeCount);
        AtomicIntegerArray expectedLeaves = new AtomicIntegerArray(storeCount);
        ExecutorService writers = Executors.newFixedThreadPool(writerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < storeCount; i++) {
                DatabaseProvider.YamlStore store = provider.yamlStore(plugin, "stress-" + i).orElseThrow();
                store.set("meta.store", i).join();
                stores.add(store);
            }

            for (int writer = 0; writer < writerCount; writer++) {
                int writerId = writer;
                futures.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < writesPerWriter; i++) {
                        int storeIndex = random.nextInt(storeCount);
                        DatabaseProvider.YamlStore store = stores.get(storeIndex);
                        store.set("writer." + writerId + "." + i, "v-" + writerId + "-" + i).join();
                        expectedLeaves.incrementAndGet(storeIndex);
                        if ((i & 63) == 0) {
                            provider.healthCheck();
                        }
                    }
                }, writers));
            }

            CompletableFuture<Void> healthChecks = CompletableFuture.runAsync(() -> {
                await(start);
                for (int i = 0; i < 50; i++) {
                    provider.healthCheck();
                    sleep(5L);
                }
            }, writers);

            start.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
            healthChecks.get(5, TimeUnit.SECONDS);
            provider.flushAll().get(15, TimeUnit.SECONDS);

            waitUntilDirtyYamlStores(provider, 0, Duration.ofSeconds(5));
            waitUntilSavedYamlFiles(dataFolder, storeCount, Duration.ofSeconds(5));

            for (int i = 0; i < storeCount; i++) {
                Path file = dataFolder.resolve("storage").resolve("stress-" + i + ".yml");
                YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file.toFile());
                assertEquals(i, loaded.getInt("meta.store"));
                assertEquals(expectedLeaves.get(i), countWriterLeaves(loaded),
                        "stress store " + i + " should persist every unique runtime write");
            }
        } finally {
            writers.shutdownNow();
            provider.close();
        }
    }

    @Test
    void overlappingHealthCheckDoesNotQueueAdditionalYamlFlushes() throws Exception {
        Path dataFolder = tempDir.resolve("health-overlap");
        Plugin plugin = plugin(dataFolder, config -> {
            config.set("storage.yaml.health-flush-max-stores", 2);
            config.set("storage.executor.core-threads", 2);
            config.set("storage.executor.max-threads", 2);
            config.set("storage.executor.scale-before-queue", true);
            config.set("storage.executor.prestart-core-threads", false);
            config.set("storage.executor.prestart-local-mode", false);
        });
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
        AtomicBoolean running = privateField(provider, "healthCheckRunning");

        try {
            for (int i = 0; i < 3; i++) {
                DatabaseProvider.YamlStore store = provider.yamlStore(plugin, "store" + i).orElseThrow();
                store.set("value", String.valueOf(i)).join();
            }

            assertTrue(running.compareAndSet(false, true));
            provider.healthCheck();
            Thread.sleep(150L);
            assertEquals(0, savedYamlFiles(dataFolder), "overlapping health check must not flush YAML stores");
            assertEquals(3, dirtyYamlStores(provider));

            running.set(false);
            provider.healthCheck();
            waitUntilSavedYamlFiles(dataFolder, 2, Duration.ofSeconds(3));
            waitUntilDirtyYamlStores(provider, 1, Duration.ofSeconds(3));
        } finally {
            running.set(false);
            provider.close();
        }
    }

    @Test
    void healthCheckFlushesDirtyYamlStoresInBudgetedBatches() throws Exception {
        Path dataFolder = tempDir.resolve("health-budget");
        Plugin plugin = plugin(dataFolder, config -> {
            config.set("storage.yaml.health-flush-max-stores", 2);
            config.set("storage.executor.core-threads", 2);
            config.set("storage.executor.max-threads", 2);
            config.set("storage.executor.scale-before-queue", true);
            config.set("storage.executor.prestart-core-threads", false);
            config.set("storage.executor.prestart-local-mode", false);
        });
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);

        try {
            for (int i = 0; i < 5; i++) {
                DatabaseProvider.YamlStore store = provider.yamlStore(plugin, "store" + i).orElseThrow();
                store.set("value", String.valueOf(i)).join();
            }

            provider.healthCheck();
            waitUntilSavedYamlFiles(dataFolder, 2, Duration.ofSeconds(3));
            waitUntilDirtyYamlStores(provider, 3, Duration.ofSeconds(3));

            provider.healthCheck();
            waitUntilSavedYamlFiles(dataFolder, 4, Duration.ofSeconds(3));
            waitUntilDirtyYamlStores(provider, 1, Duration.ofSeconds(3));

            provider.healthCheck();
            waitUntilSavedYamlFiles(dataFolder, 5, Duration.ofSeconds(3));
            waitUntilDirtyYamlStores(provider, 0, Duration.ofSeconds(3));
        } finally {
            provider.close();
        }
    }

    @Test
    void storageExecutorScalesToMaxBeforeQueueingByDefault() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("scale"), config -> {
            config.set("storage.executor.core-threads", 2);
            config.set("storage.executor.max-threads", 5);
            config.set("storage.executor.queue-capacity", 128);
            config.set("storage.executor.scale-before-queue", true);
            config.set("storage.executor.allow-core-timeout", false);
            config.set("storage.executor.prestart-core-threads", false);
            config.set("storage.executor.prestart-local-mode", false);
        });
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
        CountDownLatch started = new CountDownLatch(5);
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < 5; i++) {
                int index = i;
                futures.add(provider.submitExternal("scale-test", () -> {
                    started.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS), "test release latch timed out");
                    return index;
                }));
            }

            assertTrue(started.await(3, TimeUnit.SECONDS), "storage executor should start max workers before queueing");
            DatabaseProvider.DatabaseStats stats = provider.stats();
            assertEquals(5, stats.poolSize());
            assertEquals(0, stats.queuedTasks());
        } finally {
            release.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
            provider.close();
        }
    }

    @Test
    void noisyRequesterCannotConsumeAnotherOwnersAdmissionBudget() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("owner-admission"), config -> {
            config.set("storage.executor.core-threads", 1);
            config.set("storage.executor.max-threads", 1);
            config.set("storage.executor.queue-capacity", 128);
            config.set("storage.executor.scale-before-queue", true);
            config.set("storage.executor.prestart-core-threads", false);
            config.set("storage.executor.prestart-local-mode", false);
            config.set("database.query.default-plugin-max-queued-tasks", 2);
        });
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            CompletableFuture<Integer> running = provider.submitExternal("noisy:running", () -> {
                started.countDown();
                assertTrue(release.await(3L, TimeUnit.SECONDS));
                return 0;
            });
            assertTrue(started.await(3L, TimeUnit.SECONDS));
            CompletableFuture<Integer> noisyOne = provider.submitExternal("noisy:one", () -> 1);
            CompletableFuture<Integer> noisyTwo = provider.submitExternal("noisy:two", () -> 2);
            CompletableFuture<Integer> noisyOverflow = provider.submitExternal("noisy:overflow", () -> 3);
            CompletableFuture<Integer> quiet = provider.submitExternal("quiet:one", () -> 4);

            assertTrue(noisyOverflow.isCompletedExceptionally(), "owner admission must reject before filling the global queue");
            assertThrows(CompletionException.class, noisyOverflow::join);
            assertFalse(quiet.isCompletedExceptionally(), "a different owner retains its own admission budget");

            release.countDown();
            CompletableFuture.allOf(running, noisyOne, noisyTwo, quiet).get(3L, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            provider.close();
        }
    }

    @Test
    void queueWaitIsMeasuredSeparatelyFromFastExecution() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("queue-wait"), config -> {
            config.set("storage.executor.core-threads", 1);
            config.set("storage.executor.max-threads", 1);
            config.set("storage.executor.queue-capacity", 128);
            config.set("storage.executor.scale-before-queue", true);
            config.set("storage.executor.prestart-core-threads", false);
            config.set("storage.executor.prestart-local-mode", false);
            config.set("storage.executor.queue-wait-warn-ms", 10_000L);
        });
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            CompletableFuture<Integer> blocker = provider.submitExternal("owner:blocker", () -> {
                started.countDown();
                assertTrue(release.await(3L, TimeUnit.SECONDS));
                return 1;
            });
            assertTrue(started.await(3L, TimeUnit.SECONDS));
            CompletableFuture<Integer> fast = provider.submitExternal("owner:fast", () -> 2);
            Thread.sleep(80L);
            release.countDown();

            assertEquals(1, blocker.get(3L, TimeUnit.SECONDS));
            assertEquals(2, fast.get(3L, TimeUnit.SECONDS));
            assertTrue(provider.queueWaitSamples() >= 2L);
            assertTrue(provider.maxQueueWaitMillis() >= 50L,
                    "queued fast work must expose backlog delay even when execution itself is immediate");
        } finally {
            release.countDown();
            provider.close();
        }
    }

    @Test
    void forcedShutdownCompletesQueuedFutureAndReleasesOwnerAdmission() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("forced-shutdown"), config -> {
            config.set("storage.executor.core-threads", 1);
            config.set("storage.executor.max-threads", 1);
            config.set("storage.executor.queue-capacity", 128);
            config.set("storage.executor.scale-before-queue", true);
            config.set("storage.executor.prestart-core-threads", false);
            config.set("storage.executor.prestart-local-mode", false);
            config.set("storage.executor.shutdown-timeout-ms", 75L);
        });
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            provider.submitExternal("owner:blocker", () -> {
                started.countDown();
                release.await();
                return 1;
            });
            assertTrue(started.await(3L, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = provider.submitExternal("owner:queued", () -> 2);

            provider.close();

            assertTrue(queued.isCompletedExceptionally(), "drained accepted task must reach a terminal future state");
            assertThrows(CompletionException.class, queued::join);
            Map<?, ?> queuedByOwner = privateField(provider, "queuedTasksByOwner");
            assertTrue(queuedByOwner.isEmpty(), "forced shutdown must release all queued owner admissions");
        } finally {
            release.countDown();
            provider.close();
        }
    }

    @Test
    void platformSqliteCheckpointCannotMultiplyTheSharedShutdownDeadline() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("platform-checkpoint-deadline"), config -> {
            config.set("storage.executor.shutdown-timeout-ms", 100L);
            config.set("storage.sqlite.checkpoint-on-close", true);
        });
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
        HikariDataSource blockedSource = mock(HikariDataSource.class);
        when(blockedSource.getConnection()).thenAnswer(invocation -> {
            Thread.sleep(5_000L);
            return null;
        });
        setPrivateField(provider, "mode", StorageMode.SQLITE);
        setPrivateField(provider, "dataSource", blockedSource);

        long started = System.nanoTime();
        provider.close();
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(tookMillis < 500L,
                "platform checkpoint must respect the shared storage shutdown deadline; took=" + tookMillis);
    }

    @Test
    void saveFutureIncludesMutationThatArrivesAfterFirstSnapshot() throws Exception {
        Plugin plugin = plugin(tempDir.resolve("plugin"));
        DefaultDatabaseProvider provider = new DefaultDatabaseProvider(plugin);
        ControlledExecutor executor = new ControlledExecutor();
        File file = tempDir.resolve("storage").resolve("race.yml").toFile();
        DatabaseProvider.YamlStore store = newYamlStore(provider, plugin, file, executor);
        Object fileWriteLock = privateField(store, "fileWriteLock");

        try {
            store.set("first", "1").join();
            CompletableFuture<Void> saveFuture;
            Thread firstSaveThread;

            synchronized (fileWriteLock) {
                saveFuture = store.save();
                firstSaveThread = new Thread(executor.take(), "yaml-save-first");
                firstSaveThread.start();
                waitUntilBlocked(firstSaveThread, Duration.ofSeconds(3));
                store.set("second", "2").join();
            }

            firstSaveThread.join(TimeUnit.SECONDS.toMillis(3));
            if (firstSaveThread.isAlive()) {
                fail("First YAML save did not finish after releasing fileWriteLock");
            }

            while (!saveFuture.isDone()) {
                executor.take().run();
            }
            saveFuture.join();

            YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
            assertEquals("1", loaded.getString("first"));
            assertEquals("2", loaded.getString("second"));
            assertFalse(store.dirty(), "follow-up save should clear dirty state after persisting late mutation");
        } finally {
            provider.close();
        }
    }

    private static DatabaseProvider.YamlStore newYamlStore(DefaultDatabaseProvider provider,
                                                          Plugin plugin,
                                                          File file,
                                                          Executor executor) throws Exception {
        Class<?> type = Class.forName(DefaultDatabaseProvider.class.getName() + "$SimpleYamlStore");
        Constructor<?> constructor = type.getDeclaredConstructor(
                DefaultDatabaseProvider.class,
                Plugin.class,
                String.class,
                String.class,
                File.class,
                Executor.class,
                boolean.class
        );
        constructor.setAccessible(true);
        return (DatabaseProvider.YamlStore) constructor.newInstance(
                provider,
                plugin,
                "ledat_platform_test",
                "race",
                file,
                executor,
                false
        );
    }

    private static int countWriterLeaves(YamlConfiguration loaded) {
        ConfigurationSection writerRoot = loaded.getConfigurationSection("writer");
        if (writerRoot == null) {
            return 0;
        }
        int count = 0;
        for (String writerKey : writerRoot.getKeys(false)) {
            ConfigurationSection writerSection = writerRoot.getConfigurationSection(writerKey);
            if (writerSection != null) {
                count += writerSection.getKeys(false).size();
            }
        }
        return count;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "stress start latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for stress start latch");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T privateField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void waitUntilBlocked(Thread thread, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED) {
                return;
            }
            if (!thread.isAlive()) {
                fail("YAML save thread finished before reaching the guarded file write");
            }
            Thread.sleep(10L);
        }
        fail("YAML save thread did not block on fileWriteLock in time; state=" + thread.getState());
    }

    private static void waitUntilSavedYamlFiles(Path dataFolder, int expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            int actual = savedYamlFiles(dataFolder);
            if (actual >= expected) {
                assertEquals(expected, actual, "health flush should not exceed configured YAML store budget");
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, savedYamlFiles(dataFolder), "timed out waiting for health YAML flush");
    }

    private static int savedYamlFiles(Path dataFolder) throws Exception {
        Path storageFolder = dataFolder.resolve("storage");
        if (!Files.isDirectory(storageFolder)) {
            return 0;
        }
        try (var files = Files.list(storageFolder)) {
            return (int) files
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .count();
        }
    }

    private static void waitUntilDirtyYamlStores(DefaultDatabaseProvider provider, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            int actual = dirtyYamlStores(provider);
            if (actual == expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, dirtyYamlStores(provider), "timed out waiting for dirty YAML store count");
    }

    private static int dirtyYamlStores(DefaultDatabaseProvider provider) {
        return (int) provider.storageUnits().values().stream()
                .filter(unit -> "yaml".equals(unit.type()) && unit.dirty())
                .count();
    }

    private static Plugin plugin(Path dataFolder) {
        return plugin(dataFolder, ignored -> {
        });
    }

    private static Plugin plugin(Path dataFolder, Consumer<YamlConfiguration> customizer) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.mode", "YAML");
        config.set("storage.yaml.write-through", false);
        config.set("storage.yaml.backup-before-replace", false);
        config.set("storage.standard-layout.default-yaml-file", "data");
        config.set("storage.standard-layout.default-sqlite-file", "data");
        config.set("storage.executor.shutdown-timeout-ms", 1000L);
        customizer.accept(config);
        Logger logger = Logger.getLogger("DefaultDatabaseProviderYamlStoreTest");
        return (Plugin) Proxy.newProxyInstance(
                DefaultDatabaseProviderYamlStoreTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConfig" -> config;
                    case "reloadConfig" -> null;
                    case "getLogger" -> logger;
                    case "getName" -> "LeDatPlatformTest";
                    case "getDataFolder" -> dataFolder.toFile();
                    case "isEnabled" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class || type == long.class) {
            return 0;
        }
        if (type == float.class || type == double.class) {
            return 0.0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class ControlledExecutor implements Executor {
        private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        Runnable take() throws InterruptedException {
            Runnable task = tasks.poll(3L, TimeUnit.SECONDS);
            assertNotNull(task, "Expected queued YAML save task");
            return task;
        }
    }
}
