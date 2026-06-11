plugins {
    id("fabric-loom") version "1.15-SNAPSHOT"
}

group = "org.example"
version = "1.6.0"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven {
        url = uri("https://maven.notenoughupdates.org/releases/")
        name = "NotEnoughUpdates"
    }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.4:v2")
    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")

    // MoulConfig - using version 4.5.0 (confirmed working in other projects)
    // Artifact modern-1.21.11 exists for both 4.5.0 and 4.6.0
    modImplementation("org.notenoughupdates.moulconfig:modern-1.21.11:4.5.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    include("org.notenoughupdates.moulconfig:modern-1.21.11:4.5.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}