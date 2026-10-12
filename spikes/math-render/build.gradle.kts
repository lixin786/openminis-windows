// P2-4 spike: JLaTeXMath inside multiplatform-markdown-renderer 0.28 (same versions as the app).
plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}
kotlin { jvmToolchain(17) }

val skikoVersion = "0.8.18"
val windowsRuntime by configurations.creating { isTransitive = false }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-jvm:0.28.0")
    implementation("org.scilab.forge:jlatexmath:1.0.7")
    windowsRuntime("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skikoVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    System.getProperty("spike.shots")?.let { systemProperty("spike.shots", it) }
}

compose.desktop { application { mainClass = "spike.math.MainKt" } }

// java -jar math-render-spike-windows-x64.jar   (Java 17+, Windows x64)
val windowsJar by tasks.registering(Jar::class) {
    group = "distribution"
    archiveFileName.set("math-render-spike-windows-x64.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true
    manifest { attributes["Main-Class"] = "spike.math.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        (configurations.runtimeClasspath.get().filterNot { it.name.contains("skiko-awt-runtime") } + windowsRuntime.resolve())
            .distinct().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "META-INF/versions/9/module-info.class", "module-info.class")
}
