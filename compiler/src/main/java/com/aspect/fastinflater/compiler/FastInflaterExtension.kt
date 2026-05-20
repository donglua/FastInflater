package com.aspect.fastinflater.compiler

import org.gradle.api.provider.Property

interface FastInflaterExtension {
    val packageName: Property<String>
    val applicationId: Property<String>
}
