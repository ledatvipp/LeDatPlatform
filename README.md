<div align="center">

# LeDatPlatform

### A shared Paper and Folia foundation for Minecraft plugins

One runtime. A stable public API. The common services LeDat plugins need.

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Paper](https://img.shields.io/badge/Paper-1.21.4%2B-4040C0)](https://papermc.io/)
[![Folia](https://img.shields.io/badge/Folia-supported-6B4FBB)](https://papermc.io/software/folia)
[![Build with Gradle](https://img.shields.io/badge/build-Gradle-02303A?logo=gradle)](https://gradle.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

LeDatPlatform is an open core plugin for Paper and Folia servers. It provides a shared service layer so dependent plugins can use consistent scheduling, storage, commands, GUIs, dialogs, forms, language bundles, integrations, and diagnostics instead of shipping separate implementations.

## What is included

| Module | Purpose |
| --- | --- |
| `ledat-platform-api` | Public, compile-only service contracts for dependent plugins. |
| `ledat-platform-common` | Shared compatibility utilities used by the platform core. |
| `ledat-platform` | The server plugin that implements and registers the API. |

Highlights include Folia-aware task routing, YAML/SQLite/MySQL storage services, managed executors, command registration, guarded inventory GUIs, Paper dialogs with inventory fallback, Floodgate forms, PlaceholderAPI routing, hook management, audit and ledger services, and runtime health commands.

## Requirements

- Java 21 or newer
- Paper 1.21.4+ or a compatible Folia server
- Gradle Wrapper (included)

Some optional features activate only when their companion plugin is installed. Paper Dialogs require a compatible Paper/client version; other clients use the configured fallback where available.

## Build

On Windows:

```powershell
./gradlew.bat :ledat-platform:shadowJar :ledat-platform-api:jar
```

On Linux or macOS:

```bash
./gradlew :ledat-platform:shadowJar :ledat-platform-api:jar
```

Artifacts:

```text
ledat-platform/build/libs/LeDatPlatform-1.0.0.jar
ledat-platform-api/build/libs/ledat-platform-api-1.0.0.jar
```

The standard runtime jar uses Paper's library loader for SQLite and MySQL drivers. Allow the server to download those libraries on first startup, or use `:ledat-platform:offlineFullShadowJar` when an offline distribution with bundled database drivers is needed.

## Install the runtime

1. Build `LeDatPlatform-1.0.0.jar` or download it from a project release.
2. Put the jar in the server's `plugins/` directory.
3. Install any optional integrations your server uses.
4. Start the server and configure `plugins/LeDatPlatform/config.yml`.

The core registers its API through Bukkit's `ServicesManager`. Load dependent plugins after `LeDatPlatform` by declaring it as a hard dependency.

## Use the API from a plugin

Add the API jar as a compile-only dependency. It is supplied at runtime by `LeDatPlatform` and should not be shaded into the dependent plugin.

```kotlin
dependencies {
    compileOnly(files("libs/ledat-platform-api-1.0.0.jar"))
}
```

Declare the runtime dependency in `plugin.yml`:

```yaml
depend:
  - LeDatPlatform
```

Get the shared API from your plugin:

```java
import vn.ledat.platform.api.LeDatPlatformApi;
import vn.ledat.platform.api.LeDatPlatformProvider;

public final class ExamplePlugin extends JavaPlugin {
    private LeDatPlatformApi platform;

    @Override
    public void onEnable() {
        platform = LeDatPlatformProvider.get();
    }
}
```

Use public types under `vn.ledat.platform.api.*`. Classes under `vn.ledat.platform.core.*` are implementation details and are not a supported plugin integration surface.

## Admin commands

Grant `ledatplatform.admin` to access diagnostics and management commands, including:

```text
/ledatplatform status
/ledatplatform doctor all
/ledatplatform storage status
/ledatplatform hooks
/ledatplatform placeholders
/ledatplatform reload
```

Run `/ledatplatform` in game for the commands registered by the installed version.

## Integrations

The runtime includes optional integrations for PlaceholderAPI, Vault, LuckPerms, ItemsAdder, Oraxen, Nexo, MMOItems, PlayerPoints, and Floodgate/Geyser. Each integration is enabled only when its companion plugin is available.

## Contributing

Issues and pull requests are welcome. Please describe the Paper/Folia version, Java version, and reproduction steps for bug reports. Keep changes focused on public API contracts or shared platform behavior.

## License

LeDatPlatform is released under the [MIT License](LICENSE).

---

<div align="center">

Built for Minecraft server developers by **LeDat**.

</div>
