plugins {
    // Gradle TOML alias error को बाईपास करने के लिए डायरेक्ट kotlin("jvm") लगा दिया है
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Spotify API (Ktor, Coroutines, Serialization) के लिए ज़रूरी डिपेंडेंसीज़ 
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
}
