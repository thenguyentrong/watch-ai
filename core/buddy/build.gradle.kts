plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
