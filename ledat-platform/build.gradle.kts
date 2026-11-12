import java.util.zip.ZipFile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

dependencies {
    implementation(project(":ledat-platform-api"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.kyori:adventure-api:4.17.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.17.0")
    compileOnly("net.kyori:adventure-text-serializer-plain:4.17.0")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    // Paper resolves the production driver from plugin.yml libraries. Tests keep
    // their own runtime copy without adding SQLite to the shaded plugin classpath.
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.slf4j:slf4j-jdk14:2.0.16")
    implementation("com.tcoded:FoliaLib:0.5.1")

    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-mysql:2.0.5")
    // Minecraft 1.21.9+ object components. Runtime-only keeps the main artifact compatible with Paper 1.21.4.
    testRuntimeOnly("net.kyori:adventure-api:4.26.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val offlineFullLibraries by configurations.creating
dependencies {
    // SQLite is normally Paper-provided through plugin.yml, but the offline distribution must carry its driver.
    offlineFullLibraries("org.xerial:sqlite-jdbc:3.46.1.3")
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("LeDatPlatform")
    archiveClassifier.set("")
    mergeServiceFiles()
    // Paper/Spigot loads SQLite from plugin.yml libraries. Keeping its native
    // binaries outside the core jar cuts ~16 MB while preserving SQLite support.
    dependencies {
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        exclude(dependency("com.mysql:mysql-connector-j:.*"))
    }
    // Module descriptors from shaded dependencies no longer describe the
    // relocated plugin jar and can collide when more than one is bundled.
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
    exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "LICENSE*", "NOTICE*")
    relocate("com.zaxxer.hikari", "vn.ledat.platform.core.libs.hikari")
    relocate("com.mysql", "vn.ledat.platform.core.libs.mysql")
    relocate("org.slf4j", "vn.ledat.platform.core.libs.slf4j")
    relocate("com.tcoded.folialib", "vn.ledat.platform.core.libs.folialib")
}

/**
 * Offline server distribution: identical Platform code and API surface, with JDBC drivers bundled instead of
 * relying on Paper's library downloader. Keep this separate from the regular release jar verified below.
 */
val offlineFullShadowJar by tasks.registering(ShadowJar::class) {
    archiveBaseName.set("LeDatPlatform")
    archiveClassifier.set("offline-full")
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get(), offlineFullLibraries)
    mergeServiceFiles()
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
    exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "LICENSE*", "NOTICE*")
    relocate("com.zaxxer.hikari", "vn.ledat.platform.core.libs.hikari")
    relocate("com.mysql", "vn.ledat.platform.core.libs.mysql")
    relocate("org.slf4j", "vn.ledat.platform.core.libs.slf4j")
    relocate("com.tcoded.folialib", "vn.ledat.platform.core.libs.folialib")
}

/** Guards the single-jar distribution contract for CI and release builds. */
tasks.register("verifyReleaseJar") {
    group = "verification"
    description = "Verifies the Paper-library-based LeDatPlatform production artifact."
    dependsOn(tasks.shadowJar)
    doLast {
        val standard = tasks.shadowJar.get().archiveFile.get().asFile
        ZipFile(standard).use { jar ->
            val entryNames = jar.entries().asSequence().map { it.name }.toList()
            val duplicateEntries = entryNames.groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            check(duplicateEntries.isEmpty()) {
                "${standard.name} contains duplicate entries and cannot be remapped: ${duplicateEntries.sorted()}"
            }
            check(entryNames.none { entry ->
                entry == "module-info.class" ||
                    (entry.startsWith("META-INF/versions/") && entry.endsWith("/module-info.class"))
            }) {
                "${standard.name} contains a module descriptor from a shaded dependency."
            }
            val entries = entryNames.toSet()
            check("vn/ledat/platform/core/integration/BuiltInIntegrations.class" in entries) {
                "Built-in integrations are missing from ${standard.name}."
            }
            check(entries.none { it.startsWith("me/clip/placeholderapi/") }) {
                "PlaceholderAPI must stay a server-provided dependency, not a shaded API."
            }
            check(entries.none { it.startsWith("org/sqlite/") || it.startsWith("com/mysql/") }) {
                "The standard jar must not embed database drivers."
            }
            val pluginYml = jar.getInputStream(jar.getEntry("plugin.yml")).bufferedReader().use { it.readText() }
            check(pluginYml.contains("org.xerial:sqlite-jdbc:")) {
                "plugin.yml must let Paper resolve the SQLite driver."
            }
            check(pluginYml.contains("com.mysql:mysql-connector-j:")) {
                "plugin.yml must let Paper resolve the MySQL driver."
            }
            listOf("PlaceholderAPI", "Vault", "LuckPerms", "ItemsAdder", "Oraxen", "Nexo", "MMOItems", "PlayerPoints")
                .forEach { dependency -> check(pluginYml.contains(dependency)) { "plugin.yml is missing soft-depend $dependency." } }
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar, offlineFullShadowJar)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
