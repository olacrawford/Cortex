plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.cortex.Cortex"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jline:jline:3.30.16")
    implementation("com.github.ajalt.mordant:mordant:3.1.0")
    implementation("com.github.ajalt.mordant:mordant-markdown:3.1.0")
    implementation("com.anthropic:anthropic-java:2.61.0")
    implementation("com.openai:openai-java:4.58.0")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName = "cortex"
    archiveClassifier = ""
    mergeServiceFiles()
    isZip64 = true
}