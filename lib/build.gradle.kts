plugins {
    id("com.android.library")
    `maven-publish`
}

val libVersion: String by rootProject.extra
val libGroup: String by rootProject.extra

group = libGroup
version = libVersion

android {
    namespace = "com.aspect.fastinflater"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.annotation:annotation:1.7.1")
    compileOnly("androidx.databinding:databinding-runtime:8.2.0")
    compileOnly("androidx.recyclerview:recyclerview:1.3.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = libGroup
                artifactId = "fast-inflater"
                version = libVersion

                pom {
                    name.set("FastInflater")
                    description.set("High-performance Android LayoutInflater with view pooling, async inflate, and adaptive tuning.")
                    url.set("https://github.com/donglua/FastInflater")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/donglua/FastInflater.git")
                        developerConnection.set("scm:git:ssh://github.com/donglua/FastInflater.git")
                        url.set("https://github.com/donglua/FastInflater")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/donglua/FastInflater")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                        ?: project.findProperty("gpr.user") as String?
                    password = System.getenv("GITHUB_TOKEN")
                        ?: project.findProperty("gpr.token") as String?
                }
            }
        }
    }
}
