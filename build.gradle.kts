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
    maven {
        url = uri("https://jitpack.io")
        name = "jitpack"
    }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.4:v2")
    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")

    // MoulConfig - try direct GitHub repo
    modImplementation(include("com.github.nea89o:MoulConfig:3.0.0-beta.14")!!)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}