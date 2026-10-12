// Standalone spike build — NOT part of the product. Build: ../../src/desktop/gradlew -p . windowsJar
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google(); maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
}
dependencyResolutionManagement {
    repositories { mavenCentral(); google(); maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
}
rootProject.name = "math-render-spike"
