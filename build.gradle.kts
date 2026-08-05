plugins {
    id("net.fabricmc.fabric-loom") version "1.17.12"
}

group = "org.example"
version = "1.7.0"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven {
        url = uri("https://maven.notenoughupdates.org/releases/")
        name = "NotEnoughUpdates"
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.1")
    // MC 26.1+ is unobfuscated — no mappings or remapping needed
    implementation("net.fabricmc:fabric-loader:0.18.4")
    implementation("net.fabricmc.fabric-api:fabric-api:0.144.3+26.1")

    // MoulConfig - updated for MC 26.1
    implementation("org.notenoughupdates.moulconfig:modern-26.1:4.5.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    include("org.notenoughupdates.moulconfig:modern-26.1:4.5.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}