plugins { java }

version = "3.2.3-integrated-20261020.1-gimmick.1"

// Optional local mirror for reproducible offline builds; never packaged in the plugin.
providers.gradleProperty("localDependencyRepo").orNull?.let { localRepo ->
    repositories { maven { url = uri(localRepo) } }
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar {
    archiveBaseName.set("portable-vfx-paper")
    dependsOn(":common:classes")
    from(project(":common").extensions.getByType<SourceSetContainer>()["main"].output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test { useJUnitPlatform() }
