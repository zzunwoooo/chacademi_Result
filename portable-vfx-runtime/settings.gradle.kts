pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            content { includeGroupByRegex("net\\.fabricmc.*"); includeGroup("fabric-loom") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "portable-vfx-unity-runtime"
include("common", "paper")
if (!providers.gradleProperty("skipClient").isPresent) include("client")
