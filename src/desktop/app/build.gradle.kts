// OpenMinis Desktop — Windows port of the OpenMinis Android app.
// Copyright (C) OpenMinis contributors. Licensed under the GNU GPL v3 (see LICENSE).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "com.openminis"
version = "1.14.0"

kotlin {
    jvmToolchain(17)
}

// Windows natives for the cross-built uber-jar. Skiko (Compose's Skia binding) ships one
// native runtime jar per OS/arch; `compose.desktop.currentOs` on a Linux build host would
// bundle Linux natives, so the Windows jar swaps in skiko-awt-runtime-windows-x64.
// Keep in sync with the skiko version pulled in by the Compose plugin (1.7.3 -> 0.8.18).
val skikoVersion = "0.8.18"
val windowsRuntime by configurations.creating { isTransitive = false }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    // Android ships org.json in the framework; on the JVM we pull the reference impl.
    implementation("org.json:json:20240303")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-jvm:0.28.0")
    // Pure-Java jieba (Apache-2.0) — stands in for upstream's CppJieba JNI in shared/JiebaEngine.kt.
    implementation("com.huaban:jieba-analysis:1.0.2")

    windowsRuntime("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skikoVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Upstream Android provider unit tests are JUnit 4; run them on the JUnit Platform.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    // Off-screen PNGs of the main window for visual review: ./gradlew :desktop:test -Dopenminis.screenshots=/tmp/shots --tests '*ScreenshotHarness*'
    System.getProperty("openminis.screenshots")?.let { systemProperty("openminis.screenshots", it) }
    maxHeapSize = "1g"
    // Hotfix 1: run the whole suite with the default charset a zh-CN Windows JDK 17 has (GBK), so
    // any code that still relies on the platform charset fails here instead of on users' machines.
    jvmArgs("-Dfile.encoding=GBK", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
}

compose.desktop {
    application {
        mainClass = "com.openminis.desktop.MainKt"
        // UTF-8 defaults for the packaged MSI/EXE (the code does not depend on them — every decode is
        // explicit — but third-party code might). stdout/stderr.encoding are JDK 19+ names, harmless on 17.
        // sun.jnu.encoding is deliberately NOT overridden: it must match the OS code page for file names.
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Djava.net.useSystemProxies=true", "-Xmx1g")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "OpenMinis"
            packageVersion = "1.14.0"
            // ASCII only: Compose writes these into jpackage's @args file, and jpackage on Chinese
            // (GBK / non-UTF-8) Windows reads that file in the ANSI code page and fails with
            // "Input length = 1" on any non-ASCII byte — even when Gradle runs with
            // -Dfile.encoding=UTF-8 (a ~/.gradle/gradle.properties org.gradle.jvmargs or an IDE
            // build replaces ours). Checked by requireAsciiPackagingMetadata below.
            description = PACKAGING_DESCRIPTION
            vendor = PACKAGING_VENDOR
            copyright = PACKAGING_COPYRIGHT
            licenseFile.set(rootProject.file("LICENSE"))
            // From `gradlew :desktop:suggestRuntimeModules` + TLS (EC ciphers) + Windows accessibility bridge.
            modules("java.instrument", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.accessibility")
            windows {
                menuGroup = "OpenMinis"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Stable upgrade GUID so newer MSIs replace older installs.
                upgradeUuid = "6a1f6c0e-7d3e-4f43-9a51-0b1d6b7e2c11"
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
        }
    }
}

// jpackage metadata (see the nativeDistributions comment). Fails the build early with a clear
// message instead of jpackage's "Input length = 1" on Chinese Windows.
val PACKAGING_DESCRIPTION get() = "OpenMinis AI agent (desktop)"
val PACKAGING_VENDOR get() = "OpenMinis contributors"
val PACKAGING_COPYRIGHT get() = "GPL-3.0 - OpenMinis contributors"
val requireAsciiPackagingMetadata by tasks.registering {
    group = "verification"
    description = "Fails if the jpackage metadata contains non-ASCII characters."
    doLast {
        val bad = mapOf("description" to PACKAGING_DESCRIPTION, "vendor" to PACKAGING_VENDOR, "copyright" to PACKAGING_COPYRIGHT, "packageName" to "OpenMinis")
            .filterValues { v -> v.any { it.code > 0x7E || it.code < 0x20 } }
        if (bad.isNotEmpty()) throw GradleException("jpackage metadata must be ASCII (jpackage reads its @args file in the ANSI code page on Chinese Windows): $bad")
    }
}
tasks.configureEach {
    if (name.startsWith("package") || name.startsWith("createDistributable") || name.startsWith("createReleaseDistributable"))
        dependsOn(requireAsciiPackagingMetadata)
}

// Fat jar with Windows x64 natives — runnable on Windows with `java -jar` (Java 17+).
// Works from any build host, unlike jpackage-based MSI/EXE.
val windowsUberJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Builds OpenMinis-windows-x64-<version>.jar (needs Java 17+ on Windows)."
    archiveBaseName.set("OpenMinis-windows-x64")
    archiveVersion.set(project.version.toString())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true
    manifest { attributes["Main-Class"] = "com.openminis.desktop.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        (configurations.runtimeClasspath.get().filterNot { it.name.contains("skiko-awt-runtime") } +
            windowsRuntime.resolve())
            .distinct()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "META-INF/versions/9/module-info.class", "module-info.class")
}
