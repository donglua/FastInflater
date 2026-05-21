package com.github.donglua.fastinflater.compiler

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class GenerateLayoutsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val layoutDirs: ListProperty<File>

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        if (out.exists()) out.deleteRecursively()
        out.mkdirs()

        val pkg = packageName.get()
        val parser = LayoutXmlParser()
        val generator = LayoutCodeGenerator(pkg, out)

        val registryEntries = mutableListOf<RegistryEntry>()

        layoutDirs.get().forEach { dir ->
            dir.listFiles { f -> f.extension == "xml" }?.forEach { xml ->
                val layoutName = xml.nameWithoutExtension
                val tree = parser.parse(xml) ?: run {
                    logger.lifecycle("[fastinflater] skip $layoutName (parse failed)")
                    return@forEach
                }
                if (!tree.isFullySupported) {
                    logger.lifecycle("[fastinflater] skip $layoutName (unsupported nodes)")
                    return@forEach
                }
                val className = "Gen_${layoutName.replaceFirstChar { it.uppercase() }}"
                generator.generate(layoutName, className, tree)
                registryEntries.add(RegistryEntry(layoutName, "$pkg.$className"))
            }
        }

        generator.generateRegistry(registryEntries)
        logger.lifecycle("[fastinflater] generated ${registryEntries.size} layouts -> $pkg")
    }

    data class RegistryEntry(val layoutName: String, val className: String)
}
