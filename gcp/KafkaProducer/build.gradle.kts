import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.0"
    id("com.github.davidmc24.gradle.plugin.avro") version "1.2.0"
    application
}

application{
    mainClass.set("MainKt")
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://packages.confluent.io/maven/")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.5.31")
    implementation("org.apache.logging.log4j:log4j-api-kotlin:1.0.0")
    implementation("org.apache.logging.log4j:log4j-core:2.12.4")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.12.4")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("com.google.code.gson:gson:2.9.8")
    implementation("io.confluent:kafka-avro-serializer:7.6.2")
    implementation("io.confluent:kafka-schema-registry-client-encryption-gcp:7.6.2")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}