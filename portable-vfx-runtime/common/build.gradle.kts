plugins {
    `java-library`
}

val protocolTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs dependency-free protocol round-trip, validation, and fuzz tests."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.portablevfx.protocol.ProtocolTests")
}

tasks.check {
    dependsOn(protocolTest)
}

// Tests here deliberately use a dependency-free main harness, not a JUnit engine.
tasks.test { enabled = false }
