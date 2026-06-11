plugins {
    id("fabric-loom") version "1.17"
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
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.144.3+26.1")

    // MoulConfig - updated for MC 26.1
    modImplementation("org.notenoughupdates.moulconfig:modern-26.1:4.5.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
    include("org.notenoughupdates.moulconfig:modern-26.1:4.5.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}