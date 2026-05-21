plugins {
    id("com.android.application")
}

android {
    namespace = "com.github.donglua.fastinflater.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.github.donglua.fastinflater.demo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(project(":lib"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
}
