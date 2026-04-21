    plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    application
}

application {
    mainClass.set("com.voicenova.ApplicationKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.1"
val coroutinesVersion = "1.8.1"
val koinVersion = "4.0.0"
val exposedVersion = "0.55.0"

dependencies {
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.html.builder)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)

    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)

    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    implementation(libs.lettuce.core)

    implementation(libs.logback.classic)
    implementation(libs.kotlin.logging.jvm)

    implementation(libs.hoplite.core)
    implementation(libs.hoplite.yaml)
    implementation(libs.pdfbox)
    implementation("com.alphacephei:vosk:0.3.45")
    implementation("com.sun.mail:jakarta.mail:2.0.1")

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
    testImplementation(libs.koin.test)
}

sourceSets {
    main {
        java.srcDirs("../src/main/kotlin")
        resources.srcDirs("../src/main/resources")
    }
    test {
        java.srcDirs("../src/test/kotlin")
        resources.srcDirs("../src/test/resources")
    }
}

tasks.withType<JavaExec>().configureEach {
    workingDir = rootDir
}
