import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.1.0"
}

group = "io.github.hyuunnn"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    compileOnly("io.github.skylot:jadx-core:1.5.5")
    compileOnly("io.github.skylot:jadx-gui:1.5.5")
    // present at runtime inside jadx's fat jar; needed on the compile classpath
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    compileOnly("com.fifesoft:rsyntaxtextarea:3.6.1")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("me.friwi:jcefmaven:135.0.20")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// shadowJar owns the artifact: the plain jar would miss the bundled deps
tasks.jar { enabled = false }

tasks.shadowJar {
    archiveBaseName = "jadx-slides"
    archiveClassifier = ""

    // jadx-gui's fat jar ships its own (older) kotlin-stdlib on the parent
    // classloader; without relocation our classes would resolve kotlin.*
    // there and hit missing-API errors at runtime
    relocate("kotlin", "jadxslides.shadow.kotlin")
    relocate("fi.iki.elonen", "jadxslides.shadow.nanohttpd")
    // org.cef / me.friwi must NOT be relocated: JNI registers natives by name

    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.build { dependsOn(tasks.shadowJar) }
