plugins {
    id("com.android.library") version "9.2.1" apply false
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
}

ext {
    set("libVersion", "0.4.0")
    set("libGroup", "com.github.donglua")
}
