plugins {
    java
    id("com.gradleup.shadow") version "9.0.2"
}

group = "de.derfakegamer"
version = "2.5.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1") {
        exclude(group = "net.java.dev.jna")   // via waffle-jna; Windows SSPI auth — unused (~7.8 MB)
        exclude(module = "waffle-jna")
    }
    implementation("net.dv8tion:JDA:5.2.1") {
        exclude(module = "opus-java")                 // no voice/audio
        exclude(group = "com.google.crypto.tink")     // voice-encryption only — unused (~6.6 MB)
        exclude(group = "com.google.protobuf")        // pulled in only by tink (~5 MB)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.1") {
        exclude(group = "net.java.dev.jna")
        exclude(module = "waffle-jna")
    }
    testImplementation("net.dv8tion:JDA:5.2.1") {
        exclude(module = "opus-java")
        exclude(group = "com.google.crypto.tink")
        exclude(group = "com.google.protobuf")
        exclude(group = "net.java.dev.jna")
        exclude(module = "waffle-jna")
    }
    testImplementation("com.google.code.gson:gson:2.11.0")
}

tasks.test { useJUnitPlatform() }

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.sqlite", "de.derfakegamer.sentinel.libs.sqlite")
    relocate("org.mariadb.jdbc", "de.derfakegamer.sentinel.libs.mariadb")
    relocate("net.dv8tion.jda", "de.derfakegamer.sentinel.libs.jda")
    relocate("com.fasterxml.jackson", "de.derfakegamer.sentinel.libs.jackson")
    relocate("okhttp3", "de.derfakegamer.sentinel.libs.okhttp3")
    relocate("okio", "de.derfakegamer.sentinel.libs.okio")
    // sqlite-jdbc ships native binaries for ~24 platforms (~24 MB). Keep only the ones
    // real Paper servers run on to shrink the jar; drop the exotic/mobile/legacy ones.
    exclude("org/sqlite/native/Linux-Android/**")
    exclude("org/sqlite/native/FreeBSD/**")
    exclude("org/sqlite/native/Linux/ppc64/**")
    exclude("org/sqlite/native/Linux/arm/**")
    exclude("org/sqlite/native/Linux/armv6/**")
    exclude("org/sqlite/native/Linux/armv7/**")
    exclude("org/sqlite/native/Linux/riscv64/**")
    exclude("org/sqlite/native/Linux/x86/**")
    exclude("org/sqlite/native/Linux-Musl/aarch64/**")
    exclude("org/sqlite/native/Linux-Musl/x86/**")
    exclude("org/sqlite/native/Windows/x86/**")
    exclude("org/sqlite/native/Windows/armv7/**")
    exclude("org/sqlite/native/Windows/aarch64/**")
    // kept: Linux/{x86_64,aarch64}, Linux-Musl/x86_64, Windows/x86_64, Mac/{x86_64,aarch64}
}

tasks.build { dependsOn(tasks.shadowJar) }
