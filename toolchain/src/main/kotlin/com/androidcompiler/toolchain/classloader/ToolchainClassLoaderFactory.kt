package com.androidcompiler.toolchain.classloader

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolchainClassLoaderFactory @Inject constructor() {

    private val classLoaders = mutableMapOf<String, URLClassLoader>()

    fun getOrCreate(toolId: String, jarFiles: List<File>): URLClassLoader {
        return classLoaders.getOrPut(toolId) {
            val urls = jarFiles.map { it.toURI().toURL() }.toTypedArray()
            URLClassLoader(urls, ClassLoader.getSystemClassLoader().parent)
        }
    }

    fun invokeMain(classLoader: URLClassLoader, mainClassName: String, args: Array<String>) {
        val mainClass = classLoader.loadClass(mainClassName)
        val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
        mainMethod.invoke(null, args)
    }

    fun dispose(toolId: String) {
        classLoaders.remove(toolId)?.close()
    }

    fun disposeAll() {
        classLoaders.values.forEach { it.close() }
        classLoaders.clear()
    }
}
