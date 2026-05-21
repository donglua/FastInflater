plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
}

dependencies {
    implementation(gradleApi())
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
    implementation("com.android.tools.build:gradle:9.2.1")
    implementation("com.squareup:kotlinpoet:1.16.0")
}

gradlePlugin {
    plugins {
        create("fastInflaterPlugin") {
            id = "com.github.donglua.fastinflater"
            implementationClass = "com.github.donglua.fastinflater.compiler.FastInflaterPlugin"
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
