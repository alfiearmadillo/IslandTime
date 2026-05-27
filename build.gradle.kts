plugins {
    id("fabric-loom") version "1.15-SNAPSHOT"
}

group = "org.example"
version = "1.5.0"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven {
        url = uri("https://repo.nea.moe/releases")
        name = "neaMaven"
    }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.4:v2")
    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")

    // MoulConfig - using exact Minecraft version artifact
    modImplementation("org.notenoughupdates.moulconfig:modern-1.21.11:4.6.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    include("org.notenoughupdates.moulconfig:modern-1.21.11:4.6.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}