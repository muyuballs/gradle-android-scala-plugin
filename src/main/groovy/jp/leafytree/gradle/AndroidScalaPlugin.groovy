/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jp.leafytree.gradle

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Charsets
import com.google.common.io.Resources
import net.lingala.zip4j.core.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.io.FileUtils
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.tasks.DefaultScalaSourceSet
import org.gradle.api.tasks.scala.ScalaCompile
import org.gradle.util.ConfigureUtil
import proguard.gradle.ProGuardTask

import javax.inject.Inject

/**
 * AndroidScalaPlugin adds scala language support to official gradle android plugin.
 */
public class AndroidScalaPlugin implements Plugin<Project> {
    private final FileResolver fileResolver
    @VisibleForTesting
    final Map<String, SourceDirectorySet> sourceDirectorySetMap = new HashMap<>()
    private Project project
    private Object androidPlugin
    private Object androidExtension
    private boolean isLibrary
    private File workDir
    private File scalaProguardFile
    private File testProguardFile
    private final AndroidScalaPluginExtension extension = new AndroidScalaPluginExtension()

    /**
     * Creates a new AndroidScalaPlugin with given file resolver.
     *
     * @param fileResolver the FileResolver
     */
    @Inject
    public AndroidScalaPlugin(FileResolver fileResolver) {
        this.fileResolver = fileResolver
    }

    /**
     * Registers the plugin to current project.
     *
     * @param project currnet project
     * @param androidExtension extension of Android Plugin
     */
    void apply(Project project, Object androidExtension) {
        this.project = project
        if (project.plugins.hasPlugin("android-library")) {
            isLibrary = true
            androidPlugin = project.plugins.findPlugin("android-library")
        } else {
            isLibrary = false
            androidPlugin = project.plugins.findPlugin("android")
        }
        this.androidExtension = androidExtension
        this.workDir = new File(project.buildDir, "android-scala")
        this.scalaProguardFile = new File(workDir, "proguard-scala-config.txt")
        this.testProguardFile = new File(workDir, "proguard-test-config.txt")
        updateAndroidExtension()
        updateAndroidSourceSetsExtension()
        project.afterEvaluate {
            androidExtension.testVariants.each { variant ->
                updateTestedVariantProguardTask(variant)
                updateTestVariantProguardTask(variant)
            }
            def allVariants = androidExtension.testVariants + (isLibrary ? androidExtension.libraryVariants : androidExtension.applicationVariants)
            allVariants.each { variant ->
                addAndroidScalaCompileTask(variant)
            }
        }

        // Create proguard configurations
        androidExtension.defaultConfig { proguardFile scalaProguardFile }
        project.tasks.findByName("preBuild").doLast {
            FileUtils.forceMkdir(workDir)
            scalaProguardFile.withWriter { it.write defaultScalaProguardConfig }
            testProguardFile.withWriter { it.write defaultTestProguardConfig }
        }

        // Disable preDexLibraries
        androidExtension.dexOptions.preDexLibraries = false
        project.gradle.taskGraph.whenReady { taskGraph ->
            if (androidExtension.dexOptions.preDexLibraries) {
                throw new GradleException("Currently, android-scala plugin doesn't support enabling dexOptions.preDexLibraries")
            }
        }
    }

    /**
     * Registers the plugin to current project.
     *
     * @param project currnet project
     * @param androidExtension extension of Android Plugin
     */
    public void apply(Project project) {
        if (!project.plugins.findPlugin("android") && !project.plugins.findPlugin("android-library")) {
            throw new GradleException("Please apply 'android' or 'android-library' plugin before applying 'android-scala' plugin")
        }
        apply(project, project.extensions.getByName("android"))
    }

    /**
     * Returns directory for plugin's private working directory for argument
     *
     * @param variant the Variant
     * @return
     */
    File getVariantWorkDir(Object variant) {
        new File([workDir, "variant", variant.name].join(File.separator))
    }

    /**
     * Update test variant's proguard task to execute shrinking
     *
     * @param testVariant the TestVariant
     */
    void updateTestVariantProguardTask(final Object testVariant) {
        def variantWorkDir = getVariantWorkDir(testVariant)
        def dexTask = testVariant.dex
        def proguardTask = project.tasks.create("proguard${testVariant.name.capitalize()}ByAndroidScalaPlugin", ProGuardTask)
        def sourceProguardTask
        if (isLibrary) {
            sourceProguardTask = testVariant.testedVariant.obfuscation
            if (!sourceProguardTask) {
                return
            }
        } else {
            sourceProguardTask = testVariant.obfuscation
            if (!sourceProguardTask) {
                return
            }
            proguardTask.dependsOn sourceProguardTask
            // Disable modification
            sourceProguardTask.dontobfuscate()
            sourceProguardTask.dontoptimize()
            sourceProguardTask.dontshrink()
        }
        proguardTask.dependsOn testVariant.javaCompile
        sourceProguardTask.configurationFiles.each { // TODO: Clone all options
            proguardTask.configuration(it)
        }
        proguardTask.configuration(testProguardFile)
        dexTask.dependsOn proguardTask
        proguardTask.verbose()
        proguardTask.keep("class ${testVariant.packageName}.** { *; }")
        proguardTask.keep("class ${testVariant.testedVariant.packageName}.** { *; }")
        proguardTask.printconfiguration(new File(variantWorkDir, "proguard-all-config.txt"))
        dexTask.libraries = []
        proguardTask.doFirst {
            FileUtils.forceMkdir(variantWorkDir)
            def inJars = project.files(dexTask.inputFiles + dexTask.libraries)
            def libraryJars = project.files(androidPlugin.bootClasspath)
            if (isLibrary) {
                inJars += testVariant.javaCompile.classpath
            } else {
                libraryJars += testVariant.javaCompile.classpath
            }
            proguardTask.injars(inJars)
            proguardTask.libraryjars(libraryJars - inJars)
            def proguardedFile = new File(variantWorkDir, "proguarded-classes.jar")
            proguardTask.outjars(proguardedFile, filter: "!META-INF/MANIFEST.MF")
            dexTask.inputFiles = [proguardedFile]
            dexTask.libraries = []
        }
    }

    /**
     * Update tested variant's proguard task to keep classes which test classes depend on
     *
     * @param testVariant the TestVariant
     */
    void updateTestedVariantProguardTask(final Object testVariant) {
        final def proguardTask = testVariant.testedVariant.obfuscation
        if (!proguardTask) {
            return
        }
        if (isLibrary) {
            proguardTask.dontobfuscate()
            proguardTask.dontoptimize()
            proguardTask.dontshrink()
            return
        }
        proguardTask.configuration(testProguardFile)
        final def variantWorkDir = getVariantWorkDir(testVariant.testedVariant)
        def testJavaCompileTask = testVariant.javaCompile
        final def javaCompileDestinationDir = testJavaCompileTask.destinationDir
        proguardTask.dependsOn(testJavaCompileTask)
        proguardTask.printconfiguration(new File(variantWorkDir, "proguard-all-config.txt"))
        testJavaCompileTask.classpath.each {
            proguardTask.libraryjars(it)
        }

        //proguardTask.injars(javaCompileDestinationDir)
        final def extraInJarsFile = new File(variantWorkDir, "proguard-extra-injars-config.txt")
        proguardTask.configuration(extraInJarsFile)
        proguardTask.doFirst {
            FileUtils.forceMkdir(variantWorkDir)
            extraInJarsFile.withWriter {
                it.write("-injars $javaCompileDestinationDir\n")
            }
        }

        proguardTask.doLast {
            // TODO: More elegant way
            def relativeTestFiles = []
            def destinationDirLength = javaCompileDestinationDir.canonicalPath.length()
            javaCompileDestinationDir.traverse { file ->
                if (!file.isDirectory()) {
                    relativeTestFiles.add(file.canonicalPath.substring(destinationDirLength))
                }
            }
            proguardTask.outJarFiles.each { baseFile ->
                relativeTestFiles.clone().each { relativeTestFile ->
                    if (baseFile.directory) {
                        def testFile = new File(baseFile, relativeTestFile)
                        if (testFile.exists()) {
                            if (!testFile.delete()) {
                                project.logger.warn("Can't delete '$testFile'")
                            }
                            relativeTestFiles.remove(relativeTestFile)
                        }
                    } else {
                        try {
                            def zipFile = new ZipFile(baseFile)
                            zipFile.removeFile(relativeTestFile)
                            relativeTestFiles.remove(relativeTestFile)
                        } catch (ZipException e) {
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns scala version from scala-library in given classpath.
     *
     * @param classpath the classpath contains scala-library
     * @return scala version
     */
    static String scalaVersionFromClasspath(Collection<File> classpath) {
        def urls = classpath.collect { it.toURI().toURL() }
        def classLoader = new URLClassLoader(urls.toArray(new URL[0]))
        try {
            def propertiesClass
            try {
                propertiesClass = classLoader.loadClass("scala.util.Properties\$")
            } catch (ClassNotFoundException e) {
                return null
            }
            def versionNumber = propertiesClass.MODULE$.scalaProps["maven.version.number"]
            return new String(versionNumber) // Remove reference from ClassLoader
        } finally {
            if (classLoader instanceof Closeable) {
                classLoader.close()
            }
        }
    }

    /**
     * Updates AndroidPlugin's root extension to work with AndroidScalaPlugin.
     */
    void updateAndroidExtension() {
        androidExtension.metaClass.getScala = { extension }
        androidExtension.metaClass.scala = { configureClosure ->
            ConfigureUtil.configure(configureClosure, extension)
            androidExtension
        }
    }

    /**
     * Updates AndroidPlugin's sourceSets extension to work with AndroidScalaPlugin.
     */
    void updateAndroidSourceSetsExtension() {
        androidExtension.sourceSets.each { sourceSet ->
            sourceSet.convention.plugins.scala = new DefaultScalaSourceSet(sourceSet.displayName, fileResolver)
            def scala = sourceSet.scala
            def defaultSrcDir = ["src", sourceSet.name, "scala"].join(File.separator)
            def include = "**/*.scala"
            scala.srcDir(defaultSrcDir)
            scala.getFilter().include(include);
            sourceSet.java.srcDir(defaultSrcDir) // for Android Studio
            sourceDirectorySetMap[sourceSet.name] = scala

            // TODO: more elegant way
            sourceSet.java.getFilter().include(include);
        }
    }

    /**
     * Updates AndroidPlugin's compilation task to support scala.
     *
     * @param task the JavaCompile task
     */
    void addAndroidScalaCompileTask(Object variant) {
        def javaCompileTask = variant.javaCompile
        // To prevent locking classes.jar by JDK6's URLClassLoader
        def libraryClasspath = javaCompileTask.classpath.grep { it.name != "classes.jar" }
        def scalaVersion = scalaVersionFromClasspath(libraryClasspath)
        if (!scalaVersion) {
            return
        }
        project.logger.info("scala-library version=$scalaVersion detected")
        def configurationName = "androidScalaPluginScalaCompilerFor" + javaCompileTask.name
        def configuration = project.configurations.findByName(configurationName)
        if (!configuration) {
            configuration = project.configurations.create(configurationName)
            project.dependencies.add(configurationName, "org.scala-lang:scala-compiler:$scalaVersion")
            project.dependencies.add(configurationName, "com.typesafe.zinc:zinc:0.3.5")
        }
        def variantWorkDir = getVariantWorkDir(variant)
        def destinationDir = new File(variantWorkDir, "scalaCompile") // TODO: More elegant way
        def scalaCompileTask = project.tasks.create("compile${variant.name.capitalize()}Scala", ScalaCompile)
        scalaCompileTask.source = javaCompileTask.source
        scalaCompileTask.destinationDir = destinationDir
        scalaCompileTask.sourceCompatibility = javaCompileTask.sourceCompatibility
        scalaCompileTask.targetCompatibility = javaCompileTask.targetCompatibility
        scalaCompileTask.scalaCompileOptions.encoding = javaCompileTask.options.encoding
        scalaCompileTask.options.encoding = javaCompileTask.options.encoding
        scalaCompileTask.options.bootClasspath = androidPlugin.bootClasspath.join(File.pathSeparator)
        // TODO: Remove bootClasspath
        scalaCompileTask.classpath = javaCompileTask.classpath + project.files(androidPlugin.bootClasspath)
        scalaCompileTask.scalaClasspath = configuration.asFileTree
        scalaCompileTask.zincClasspath = configuration.asFileTree
        scalaCompileTask.scalaCompileOptions.incrementalOptions.analysisFile = new File(variantWorkDir, "analysis.txt")
        if (extension.addparams) {
            scalaCompileTask.scalaCompileOptions.additionalParameters = [extension.addparams]
        }
        scalaCompileTask.doFirst {
            FileUtils.forceMkdir(destinationDir)
        }
        javaCompileTask.dependsOn.each {
            scalaCompileTask.dependsOn it
        }
        javaCompileTask.classpath = javaCompileTask.classpath + project.files(destinationDir)
        javaCompileTask.dependsOn scalaCompileTask
        javaCompileTask.doLast {
            project.ant.move(todir: javaCompileTask.destinationDir, preservelastmodified: true) {
                fileset(dir: destinationDir)
            }
        }
    }

    /**
     * Returns the proguard configuration text for test.
     *
     * @return the proguard configuration text for test
     */
    String getDefaultTestProguardConfig() {
        Resources.toString(getClass().getResource("android-test-proguard.config"), Charsets.UTF_8)
    }

    /**
     * Returns the proguard configuration text for scala.
     *
     * @return the proguard configuration text for scala
     */
    String getDefaultScalaProguardConfig() {
        Resources.toString(getClass().getResource("android-proguard.config"), Charsets.UTF_8)
    }
}
