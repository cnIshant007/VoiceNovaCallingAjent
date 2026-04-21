plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    kotlin("plugin.compose") version "2.3.20" apply false
    id("io.ktor.plugin") version "3.4.2" apply false
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.compose") version "1.10.3" apply false
}

allprojects {
    group = "com.voicenova"
    version = "1.0.0"
}
