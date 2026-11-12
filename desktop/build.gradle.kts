import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

tasks.processResources {
    from(rootProject.file("branding/icon-512.png")) {
        rename { "fcp-icon.png" }
    }
}

compose.desktop {
    application {
        mainClass = "fcp.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                TargetFormat.Deb,
                TargetFormat.Rpm,
                TargetFormat.AppImage,
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Exe,
            )
            packageName = "fcp-launcher"
            // jpackage rejects a leading zero (0.x is illegal for dmg/msi). The in-app version stays 0.3.0.
            packageVersion = "1.0.0"
            description = "FCP Launcher"
            vendor = "FCP contributors"
            copyright = "Copyright 2026 FCP contributors. GPL-3.0-or-later."
            licenseFile.set(rootProject.file("LICENSE"))
            linux {
                shortcut = true
                iconFile.set(rootProject.file("branding/icon-512.png"))
                packageName = "fcp-launcher"
            }
            macOS {
                bundleID = "xyz.easonyin.fcp"
                iconFile.set(rootProject.file("branding/icon-512.png"))
            }
            windows {
                menuGroup = "FCP Launcher"
                upgradeUuid = "7c4e1a2b-6d90-4f11-8a3e-5b0c9d1e2f33"
                iconFile.set(rootProject.file("branding/icon-512.png"))
            }
        }
    }
}
