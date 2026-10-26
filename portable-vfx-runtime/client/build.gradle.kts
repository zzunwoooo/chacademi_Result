plugins {
    id("fabric-loom") version "1.10.5"
}

version = "3.2.2-integrated-20261020.1-readychunks.1"

base { archivesName.set("portable-vfx-client") }

dependencies {
    minecraft("com.mojang:minecraft:1.21.4")
    mappings("net.fabricmc:yarn:1.21.4+build.8:v2")
    modImplementation("net.fabricmc:fabric-loader:0.16.14")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.119.4+1.21.4")
    implementation(project(":common"))
    include(project(":common"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

java { withSourcesJar() }

// Loom's synthetic mapped groups exist only in its local Maven cache. Avoid slow,
// meaningless requests for those groups to every remote repository.
repositories.withType<org.gradle.api.artifacts.repositories.MavenArtifactRepository>().configureEach {
    if (url.scheme == "https" || url.scheme == "http") {
        content {
            excludeGroupByRegex("net_fabricmc_yarn_.*")
            excludeGroup("net.minecraft")
        }
    }
}
