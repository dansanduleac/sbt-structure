package org.jetbrains.sbt

import sbt.Keys._
import sbt._
import sbt.Load.BuildStructure
import sbt.Value
import Utilities._

/**
 * @author Pavel Fatin
 */
object Extractor {
  def extractStructure(state: State): StructureData = {
    val extracted = Project.extract(state)

    val structure = extracted.structure

    val projectRef = Project.current(state)

    val projectData = extractProject(state, structure, projectRef)

    val repositoryData = Extractor.extractRepository(state, projectRef)

    StructureData(projectData, repositoryData)
  }

  def extractProject(state: State, structure: BuildStructure, projectRef: ProjectRef): ProjectData = {
    val name = Keys.name.in(projectRef, Compile).get(structure.data).get

    val base = Keys.baseDirectory.in(projectRef, Compile).get(structure.data).get

    val configurations = Seq(
      extractConfiguration(state, structure, projectRef, Compile),
      extractConfiguration(state, structure, projectRef, Test))

    val scala: Option[ScalaData] = Project.runTask(scalaInstance.in(projectRef, Compile), state) collect {
      case (_, Value(it)) => ScalaData(it.version, it.libraryJar, it.compilerJar, it.extraJars)
    }

    val project = Project.getProject(projectRef, structure).get

    val projects = project.aggregate.map(extractProject(state, structure, _))

    ProjectData(name, base, configurations, scala, projects)
  }

  def extractConfiguration(state: State, structure: BuildStructure, projectRef: ProjectRef, configuration: Configuration): ConfigurationData = {
    val sources = Keys.sourceDirectories.in(projectRef, configuration).get(structure.data).get

    val output = Keys.classDirectory.in(projectRef, configuration).get(structure.data).get

    val moduleDependencies = {
      val classpath: Option[Classpath] = Project.runTask(externalDependencyClasspath.in(projectRef, configuration), state) collect {
        case (_, Value(it)) => it
      }

      val moduleIDs = classpath.get.flatMap(_.get(Keys.moduleID.key))

      moduleIDs.map(it => ModuleIdentifier(it.organization, it.name, it.revision))
    }

    val jarDependencies: Seq[File] = {
      val classpath: Option[Classpath] = Project.runTask(unmanagedJars.in(projectRef, configuration), state) collect {
        case (_, Value(it)) => it
      }
      classpath.get.map(_.data)
    }

    ConfigurationData(configuration.name, sources, output, moduleDependencies, jarDependencies)
  }

  def extractRepository(state: State, projectRef: ProjectRef): RepositoryData = {
    val report: UpdateReport = Project.runTask(updateClassifiers.in(projectRef), state) collect {
      case (_, Value(it)) => it
    } getOrElse {
      throw new RuntimeException()
    }

    val moduleReports = report.configurations.flatMap(_.modules).distinctBy(_.module)

    val moduleDescriptors = moduleReports.map { moduleReport =>
      def artifacts(kind: String): Seq[File] = moduleReport.artifacts.filter(_._1.`type` == kind).map(_._2)

      val module = moduleReport.module

      ModuleData(ModuleIdentifier(module.organization, module.name, module.revision),
        artifacts("jar"), artifacts("doc"), artifacts("src"))
    }

    RepositoryData(new File("."), moduleDescriptors)
  }
}