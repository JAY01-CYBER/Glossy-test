plugins {
    // Isko direct TOML alias se call karein taaki version 2.4.10 correctly apply ho
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

// Ye block add karna zaroori hai taaki Gradle ko exact path pata chal jaye
sourceSets {
    main {
        java.srcDirs("src/main/kotlin")
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    testImplementation(libs.junit)
}
