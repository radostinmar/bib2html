import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.0"
    application
    id("com.github.johnrengelman.shadow") version("5.1.0")
}

group = "me.radostin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val kotlinHtmlVersion = "0.7.5"

dependencies {
    implementation("org.jbibtex:jbibtex:1.0.20")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:${kotlinHtmlVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-html:${kotlinHtmlVersion}")
    implementation("com.amazonaws:aws-java-sdk-s3:1.12.236")
    implementation("com.amazonaws:aws-lambda-java-core:1.2.1")
    implementation("com.amazonaws:aws-lambda-java-events:3.11.0")
    implementation("com.itextpdf:itextpdf:5.5.13.3")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("Handler")
}

val fatJar = task("fatJar", type = Jar::class) {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true

    archiveBaseName.set("${project.name}-fat")
    manifest {
        attributes["Implementation-Title"] = "Gradle Jar File Example"
        attributes["Implementation-Version"] = archiveVersion
        attributes["Main-Class"] = "Handler"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get() as CopySpec)
}
