plugins {
    application
    kotlin("jvm") version "2.3.21"
}

group = "com.tc"
version = "1.0.0"
repositories { mavenCentral() }
kotlin { jvmToolchain(25) }
application { mainClass.set("com.tc.gymtrainer.bot.BotAppKt") }

tasks.test { useJUnitPlatform() }

tasks.named<JavaExec>("run") {
    val localConfig = layout.projectDirectory.file(".env").asFile
    doFirst {
        if (localConfig.isFile) localConfig.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val key = line.substringBefore('=')
            require('=' in line && key.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "Invalid .env entry" }
            if (key !in environment) environment(key, line.substringAfter('='))
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("com.typesafe:config:1.4.3")
    implementation("dev.inmo:tgbotapi:24.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
}
