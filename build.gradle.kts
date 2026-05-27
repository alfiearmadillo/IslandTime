buildscript {
    repositories {
        maven("https://maven.fabricmc.net/")
        mavenCentral()
    }
    dependencies {
        classpath("net.fabricmc:fabric-loom:1.8-SNAPSHOT")
    }
}

apply(plugin = "fabric-loom")

group = "org.example"
version = "1.5.0"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven {
        url = uri("https://maven.azureaaron.net/releases")
        name = "azureaaronMaven"
        content {
            includeGroup("net.azureaaron")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings("net.fabricmc:yarn:1.21.11+build.4:v2")
    modImplementation("net.fabricmc:fabric-loader:0.18.4")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.3+1.21.11")

    // Dandelion (shades in MoulConfig) - following Skyblocker's approach
    modImplementation("net.azureaaron:dandelion:2.0.0")
    include("net.azureaaron:dandelion:2.0.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}