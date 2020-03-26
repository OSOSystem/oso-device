plugins {
    kotlin("jvm") version "1.3.61"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.zeromq:jeromq:0.5.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.10.3")
    implementation("org.postgresql:postgresql:42.2.11")
    implementation("com.github.jkcclemens:khttp:0.1.0")
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
}