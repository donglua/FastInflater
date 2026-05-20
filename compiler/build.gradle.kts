plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("com.android.tools.build:gradle:8.2.0")
    implementation("com.squareup:kotlinpoet:1.16.0")
}

gradlePlugin {
    plugins {
        create("fastInflaterPlugin") {
            id = "com.aspect.fastinflater"
            implementationClass = "com.aspect.fastinflater.compiler.FastInflaterPlugin"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}
