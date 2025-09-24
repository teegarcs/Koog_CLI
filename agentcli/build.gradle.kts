plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.teegarcs.specagent.MainKt")
}


dependencies {
    implementation(libs.koog.agents)
    implementation(libs.clikt.core)
    implementation(libs.clikt.md)
    implementation(libs.slf4j.jdk14)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    // swagger parser
    implementation(libs.swagger.parser)
}