plugins {
    java
}

allprojects {
    group = "vn.ledat.platform"
    version = "1.0.0"
}

subprojects {
    plugins.apply("java")

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-parameters")
    }
}
