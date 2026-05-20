package com.aspect.fastinflater.compiler

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

class FastInflaterPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("fastInflater", FastInflaterExtension::class.java)

        project.afterEvaluate {
            val outputDir = File(project.buildDir, "generated/source/fastinflater")

            val task = project.tasks.register(
                "generateFastInflaterLayouts",
                GenerateLayoutsTask::class.java
            ) {
                it.outputDir.set(outputDir)
                it.packageName.set(ext.packageName.orElse(defaultPackage(project)))
                it.layoutDirs.set(collectLayoutDirs(project))
            }

            registerSourceSet(project, outputDir)

            project.tasks.matching { it.name.startsWith("preBuild") || it.name.startsWith("generateDebugSources") || it.name.startsWith("generateReleaseSources") }
                .configureEach { it.dependsOn(task) }
        }
    }

    private fun defaultPackage(project: Project): String {
        val app = project.extensions.findByType(AppExtension::class.java)
        if (app != null) return "${app.namespace ?: "com.aspect.fastinflater"}.generated"
        val lib = project.extensions.findByType(LibraryExtension::class.java)
        if (lib != null) return "${lib.namespace ?: "com.aspect.fastinflater"}.generated"
        return "com.aspect.fastinflater.generated"
    }

    private fun collectLayoutDirs(project: Project): List<File> {
        val dirs = mutableListOf<File>()
        val app = project.extensions.findByType(AppExtension::class.java)
        app?.sourceSets?.forEach { ss ->
            ss.res.srcDirs.forEach { res -> dirs.add(File(res, "layout")) }
        }
        val lib = project.extensions.findByType(LibraryExtension::class.java)
        lib?.sourceSets?.forEach { ss ->
            ss.res.srcDirs.forEach { res -> dirs.add(File(res, "layout")) }
        }
        return dirs.filter { it.isDirectory }
    }

    private fun registerSourceSet(project: Project, outputDir: File) {
        val app = project.extensions.findByType(AppExtension::class.java)
        app?.sourceSets?.getByName("main")?.java?.srcDir(outputDir)
        val lib = project.extensions.findByType(LibraryExtension::class.java)
        lib?.sourceSets?.getByName("main")?.java?.srcDir(outputDir)
    }
}
