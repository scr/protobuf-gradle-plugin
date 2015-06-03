/*
 * Original work copyright (c) 2015, Alex Antonov. All rights reserved.
 * Modified work copyright (c) 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.google.protobuf.gradle

import org.gradle.api.Action
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.GradleException
import org.gradle.api.internal.file.DefaultSourceDirectorySet
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.plugins.DslObject
import org.gradle.api.internal.tasks.DefaultSourceSet
import org.gradle.api.InvalidUserDataException
import org.gradle.api.logging.LogLevel
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.tooling.UnsupportedVersionException
import org.gradle.util.CollectionUtils

import javax.inject.Inject

class ProtobufPlugin implements Plugin<Project> {
    private final FileResolver fileResolver

    @Inject
    public ProtobufPlugin(FileResolver fileResolver) {
      this.fileResolver = fileResolver;
    }

    void apply(final Project project) {
        def gv = project.gradle.gradleVersion =~ "(\\d*)\\.(\\d*).*"
        if (!gv || !gv.matches() || gv.group(1).toInteger() < 2 || gv.group(2).toInteger() < 2) {
            //throw new UnsupportedVersionException
            println("You are using Gradle ${project.gradle.gradleVersion}: This version of the protobuf plugin requires minimum Gradle version 2.2")
        }

        if (!project.plugins.hasPlugin('java')
            && !project.plugins.hasPlugin('com.android.application')) {
            throw new GradleException(
                'Please apply the \'java\' plugin or the \'com.android.application\' plugin first')
        }

        // Provides the osdetector extension
        project.apply plugin: 'osdetector'

        project.convention.plugins.protobuf = new ProtobufConvention(project, fileResolver);

        project.afterEvaluate {
          addProtoTasks(project)
          resolveProtocDep(project)
          resolveNativeCodeGenPlugins(project)
        }
    }

    private resolveProtocDep(Project project) {
        String spec = project.convention.plugins.protobuf.protocDep;
        if (spec == null) {
          return;
        }
        Configuration config = project.configurations.create('protoc') {
          visible = false
          transitive = false
          extendsFrom = []
        }
        def groupId, artifact, version
        (groupId, artifact, version) = spec.split(":")
        def notation = [group: groupId,
                        name: artifact,
                        version: version,
                        classifier: project.osdetector.classifier,
                        ext: 'exe']
        project.logger.info('Adding protoc dependency: ' + notation)
        Dependency dep = project.dependencies.add(config.name, notation)
        Set<File> files = config.files(dep)
        File protoc = null
        for (f in files) {
          if (f.getName().endsWith('.exe')) {
            protoc = f
            break
          }
        }
        if (protoc == null) {
          throw new GradleException('Cannot resolve ' + spec)
        }
        if (!protoc.canExecute() && !protoc.setExecutable(true)) {
          throw new GradleException('Cannot make ' + protoc + ' executable')
        }
        project.logger.info('Resolved protoc: ' + protoc)
        project.convention.plugins.protobuf.protocPath = protoc.getPath()
    }

    private resolveNativeCodeGenPlugins(Project project) {
        Configuration config = project.configurations.create('protobufNativeCodeGenPlugins') {
          visible = false
          transitive = false
          extendsFrom = []
        }
        def Map nameToDep = new HashMap()
        for (key in project.convention.plugins.protobuf.protobufNativeCodeGenPluginDeps) {
          def name, groupId, artifact, version
            (name, groupId, artifact, version) = key.split(":")
            def notation = [group: groupId,
                            name: artifact,
                            version: version,
                            classifier: project.osdetector.classifier,
                            ext: 'exe']
            project.logger.info('Adding a native protobuf codegen plugin dependency: ' + notation)
            Dependency dep = project.dependencies.add(config.name, notation)
            nameToDep.put(name, dep);
        }
        for (e in nameToDep) {
            Set<File> files = config.files(e.value);
            File plugin = null
            for (f in files) {
                if (f.getName().endsWith('.exe')) {
                    plugin = f
                    break
                }
            }
            if (plugin == null) {
                throw new GradleException('Cannot resolve ' + e.value)
            }
            if (!plugin.canExecute() && !plugin.setExecutable(true)) {
                throw new GradleException('Cannot make ' + plugin + ' executable')
            }
            project.logger.info('Resolved a native protobuf codegen plugin: ' + plugin)
            project.convention.plugins.protobuf.protobufCodeGenPlugins.add(
                e.key + ':' + plugin.getPath())
        }
    }


    private addProtoTasks(Project project) {
        project.protobuf.sources.all { ProtobufSourceDirectorySet sourceDirSet ->
            addTasksToProjectForSourceDirSet(project, sourceDirSet)
        }
    }

    private def addTasksToProjectForSourceDirSet(Project project, ProtobufSourceDirectorySet sourceDirSet) {
        def generateJavaTaskName = 'generate' +
            Utils.getSourceSetSubstringForTaskNames(sourceDirSet.name) + 'Proto'
        project.tasks.create(generateJavaTaskName, ProtobufCompile) {
            description = "Compiles Proto source '${sourceDirSet.name}:proto'"
            // Include extracted sources
            ConfigurableFileTree extractedProtoSources =
                project.fileTree("${project.extractedProtosDir}/${sourceDirSet.name}") {
                  include "**/*.proto"
                }
            inputs.source extractedProtoSources
            include extractedProtoSources.dir
            // Include sourceDirSet dirs
            sourceDirectorySet = sourceDirSet
            inputs.source sourceDirectorySet
            sourceDirectorySet.srcDirs.each { srcDir ->
              include srcDir
            }

            outputs.dir getGeneratedSourceDir(project, sourceDirSet.name)
            destinationDir = project.file(getGeneratedSourceDir(project, sourceDirSet.name))
        }
        def generateJavaTask = project.tasks.getByName(generateJavaTaskName)

        def extractProtosTaskName = 'extract' +
            Utils.getSourceSetSubstringForTaskNames(sourceDirSet.name) + 'Proto'
        project.tasks.create(extractProtosTaskName, ProtobufExtract) {
            description = "Extracts proto files/dependencies specified by 'protobuf' configuration"
            extractedProtosDir = project.file("${project.extractedProtosDir}/${sourceDirSet.name}")
            configName = Utils.getConfigName(sourceDirSet.name)
        }
        def extractProtosTask = project.tasks.getByName(extractProtosTaskName)
        generateJavaTask.dependsOn(extractProtosTask)

        // Add generated java sources to java source sets that will be compiled.
        if (project.hasProperty('android')) {
            // The android plugin uses its own SourceSetContainer for java source files.
            def sourceSet = project.android.sourceSets.maybeCreate(sourceDirSet.name)
            // TODO(zhangkun83): does Android plugin provide a helper to get the task names?
            // TODO(zhangkun83): the test compilation tasks are like compileDebugUnitTestJava, need to figure
            // out whether it's derived from source set name.
            sourceSet.java.srcDir(getGeneratedSourceDir(project, sourceDirSet.name))
            String compileDebugTaskName = 'compile' +
                Utils.getSourceSetSubstringForTaskNames(sourceDirSet.name) + 'DebugJava'
            String compileReleaseTaskName = 'compile' +
                Utils.getSourceSetSubstringForTaskNames(sourceDirSet.name) + 'ReleaseJava'
            project.tasks.getByName(compileDebugTaskName).dependsOn(generateJavaTask)
            project.tasks.getByName(compileReleaseTaskName).dependsOn(generateJavaTask)
        } else {
            SourceSet sourceSet = project.sourceSets.maybeCreate(sourceDirSet.name)
            sourceSet.java.srcDir(getGeneratedSourceDir(project, sourceDirSet.name))
            String compileJavaTaskName = sourceSet.getCompileTaskName("java");
            project.tasks.getByName(compileJavaTaskName).dependsOn(generateJavaTask)
        }
    }

    private getGeneratedSourceDir(Project project, String sourceSetName) {
        def generatedSourceDir = project.convention.plugins.protobuf.generatedFileDir
        return "${generatedSourceDir}/${sourceSetName}"
    }

}
