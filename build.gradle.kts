plugins {
    java
    application
}

group = "com.example"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass.set("com.example.downloader.cli.Main")
}

repositories {
    mavenCentral()
}

val bench: SourceSet = sourceSets.create("bench") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    implementation("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")

    "benchImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "benchAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.test {
    useJUnitPlatform {
        // Default: exclude integration + chaos (fast unit + property tests only).
        // `-PintegrationTests` opts in to integration tests; `-PchaosTests` opts in to chaos.
        if (!project.hasProperty("integrationTests")) excludeTags("integration")
        if (!project.hasProperty("chaosTests")) excludeTags("chaos")
    }
    jvmArgs("-ea")
}

tasks.javadoc {
    // Only document the public surface; package-private types are implementation detail.
    options.memberLevel = org.gradle.external.javadoc.JavadocMemberLevel.PUBLIC
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs JMH benchmarks. -Pjmh.include=<regex>, -Pjmh.args='<extra JMH flags>'."
    dependsOn("benchClasses")
    classpath = bench.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    // Pin the parent (and forked) JVM to the project's JDK 21 toolchain so the
    // bench classes (compiled at class file version 65) actually load.
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    val pattern = providers.gradleProperty("jmh.include").getOrElse(".*Benchmark")
    val extra = providers.gradleProperty("jmh.args").getOrElse("")
    args(pattern)
    if (!extra.isBlank()) args(extra.trim().split("\\s+".toRegex()))
}
