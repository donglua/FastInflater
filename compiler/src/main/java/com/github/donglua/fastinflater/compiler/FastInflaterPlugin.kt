package com.github.donglua.fastinflater.compiler

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class FastInflaterPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("fastInflater", FastInflaterExtension::class.java)

        project.afterEvaluate {
            val outputDir = project.layout.buildDirectory.dir("generated/source/fastinflater")

            val task = project.tasks.register(
                "generateFastInflaterLayouts",
                GenerateLayoutsTask::class.java
            ) {
                it.outputDir.set(outputDir)
                it.packageName.set(ext.packageName.orElse(defaultPackage(project)))
                it.layoutDirs.set(collectLayoutDirs(project))
            }

            registerSourceSet(project, outputDir.get().asFile)

            project.tasks.matching { it.name.startsWith("preBuild") || it.name.startsWith("generateDebugSources") || it.name.startsWith("generateReleaseSources") }
                .configureEach { it.dependsOn(task) }
        }
    }

    private fun defaultPackage(project: Project): String {
        val app = project.extensions.findByType(ApplicationExtension::class.java)
        if (app != null) return "${app.namespace ?: "com.github.donglua.fastinflater"}.generated"
        val lib = project.extensions.findByType(LibraryExtension::class.java)
        if (lib != null) return "${lib.namespace ?: "com.github.donglua.fastinflater"}.generated"
        return "com.github.donglua.fastinflater.generated"
    }

    private fun collectLayoutDirs(project: Project): List<File> {
        val dirs = mutableListOf<File>()
        val app = project.extensions.findByType(ApplicationExtension::class.java)
        app?.sourceSets?.forEach { ss ->
            ss.res.directories.forEach { res -> dirs.add(project.file(res).resolve("layout")) }
        }
        val lib = project.extensions.findByType(LibraryExtension::class.java)
        lib?.sourceSets?.forEach { ss ->
            ss.res.directories.forEach { res -> dirs.add(project.file(res).resolve("layout")) }
        }
        return dirs.filter { it.isDirectory }
    }

    private fun registerSourceSet(project: Project, outputDir: File) {
        val app = project.extensions.findByType(ApplicationExtension::class.java)
        app?.sourceSets?.getByName("main")?.java?.srcDir(outputDir)
        val lib = project.extensions.findByType(LibraryExtension::class.java)
        lib?.sourceSets?.getByName("main")?.java?.srcDir(outputDir)
    }
}
