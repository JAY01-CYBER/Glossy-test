plugins {
    kotlin("jvm") // Wapas isko bina version ke use karein
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

// Ye block sabse zaroori hai, isko mat hatana
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
