plugins {
    id("com.android.library")
}

android {
    namespace = "com.aspect.fastinflater"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.annotation:annotation:1.7.1")
    compileOnly("androidx.databinding:databinding-runtime:8.2.0")
}
