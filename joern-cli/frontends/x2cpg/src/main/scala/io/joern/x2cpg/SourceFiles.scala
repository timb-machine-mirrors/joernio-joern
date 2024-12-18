package io.joern.x2cpg

import better.files.File.VisitOptions
import better.files.*
import org.slf4j.LoggerFactory

import java.io.FileNotFoundException
import java.nio.file.FileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import scala.util.matching.Regex

import scala.jdk.CollectionConverters.SetHasAsJava

object SourceFiles {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Hack to have a FileVisitor in place that will continue iterating files even if an IOException happened during
    * traversal.
    */
  private final class FailsafeFileVisitor extends FileVisitor[Path] {

    private val seenFiles = scala.collection.mutable.Set.empty[Path]

    def files(): Set[File] = seenFiles.map(File(_)).toSet

    override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
      FileVisitResult.CONTINUE
    }

    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
      seenFiles.addOne(file)
      FileVisitResult.CONTINUE
    }

    override def visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = {
      exc match {
        case e: java.nio.file.FileSystemLoopException => logger.warn(s"Ignoring '$file' (cyclic symlink)")
        case other                                    => logger.warn(s"Ignoring '$file'", other)
      }
      FileVisitResult.CONTINUE
    }

    override def postVisitDirectory(dir: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
  }

  private def isIgnoredByFileList(filePath: String, ignoredFiles: Seq[String]): Boolean = {
    val filePathFile = File(filePath)
    if (!filePathFile.exists || !filePathFile.isReadable) {
      logger.debug(s"'$filePath' ignored (not readable or broken symlink)")
      return true
    }
    val isInIgnoredFiles = ignoredFiles.exists { ignorePath =>
      val ignorePathFile = File(ignorePath)
      ignorePathFile.exists &&
      (ignorePathFile.contains(filePathFile, strict = false) || ignorePathFile.isSameFileAs(filePathFile))
    }
    if (isInIgnoredFiles) {
      logger.debug(s"'$filePath' ignored (--exclude)")
      true
    } else {
      false
    }
  }

  private def isIgnoredByDefaultRegex(filePath: String, inputPath: String, ignoredDefaultRegex: Seq[Regex]): Boolean = {
    val relPath = toRelativePath(filePath, inputPath)
    if (ignoredDefaultRegex.exists(_.matches(relPath))) {
      logger.debug(s"'$relPath' ignored by default")
      true
    } else {
      false
    }
  }

  private def isIgnoredByRegex(filePath: String, inputPath: String, ignoredFilesRegex: Regex): Boolean = {
    val relPath               = toRelativePath(filePath, inputPath)
    val isInIgnoredFilesRegex = ignoredFilesRegex.matches(relPath)
    if (isInIgnoredFilesRegex) {
      logger.debug(s"'$relPath' ignored (--exclude-regex)")
      true
    } else {
      false
    }
  }

  /** Method to filter file based on the passed parameters
    * @param file
    * @param inputPath
    * @param ignoredDefaultRegex
    * @param ignoredFilesRegex
    * @param ignoredFilesPath
    * @return
    */
  def filterFile(
    file: String,
    inputPath: String,
    ignoredDefaultRegex: Option[Seq[Regex]] = None,
    ignoredFilesRegex: Option[Regex] = None,
    ignoredFilesPath: Option[Seq[String]] = None
  ): Boolean = !ignoredDefaultRegex.exists(isIgnoredByDefaultRegex(file, inputPath, _))
    && !ignoredFilesRegex.exists(isIgnoredByRegex(file, inputPath, _))
    && !ignoredFilesPath.exists(isIgnoredByFileList(file, _))

  def filterFiles(
    files: List[String],
    inputPath: String,
    ignoredDefaultRegex: Option[Seq[Regex]] = None,
    ignoredFilesRegex: Option[Regex] = None,
    ignoredFilesPath: Option[Seq[String]] = None
  ): List[String] = files.filter(filterFile(_, inputPath, ignoredDefaultRegex, ignoredFilesRegex, ignoredFilesPath))

  /** For given input paths, determine all source files by inspecting filename extensions and filter the result if
    * following arguments ignoredDefaultRegex, ignoredFilesRegex and ignoredFilesPath are used
    */
  def determine(
    inputPath: String,
    sourceFileExtensions: Set[String],
    ignoredDefaultRegex: Option[Seq[Regex]] = None,
    ignoredFilesRegex: Option[Regex] = None,
    ignoredFilesPath: Option[Seq[String]] = None
  )(implicit visitOptions: VisitOptions = VisitOptions.follow): List[String] = {
    filterFiles(
      determine(Set(inputPath), sourceFileExtensions),
      inputPath,
      ignoredDefaultRegex,
      ignoredFilesRegex,
      ignoredFilesPath
    )
  }

  /** For a given array of input paths, determine all source files by inspecting filename extensions.
    */
  def determine(inputPaths: Set[String], sourceFileExtensions: Set[String])(implicit
    visitOptions: VisitOptions
  ): List[String] = {
    def hasSourceFileExtension(file: File): Boolean =
      file.extension.exists(sourceFileExtensions.contains)

    val inputFiles = inputPaths.map(File(_))
    assertAllExist(inputFiles)

    val (dirs, files) = inputFiles.partition(_.isDirectory)

    val matchingFiles = files.filter(hasSourceFileExtension).map(_.toString)
    val matchingFilesFromDirs = dirs
      .flatMap { dir =>
        val visitor = new FailsafeFileVisitor
        Files.walkFileTree(dir.path, visitOptions.toSet.asJava, Int.MaxValue, visitor)
        visitor.files()
      }
      .filter(hasSourceFileExtension)
      .map(_.pathAsString)

    (matchingFiles ++ matchingFilesFromDirs).toList.sorted
  }

  /** Attempting to analyse source paths that do not exist is a hard error. Terminate execution early to avoid
    * unexpected and hard-to-debug issues in the results.
    */
  private def assertAllExist(files: Set[File]): Unit = {
    val (existent, nonExistent) = files.partition(_.exists)
    val nonReadable             = existent.filterNot(_.isReadable)
    if (nonExistent.nonEmpty || nonReadable.nonEmpty) {
      logErrorWithPaths("Source input paths do not exist", nonExistent.map(_.canonicalPath))
      logErrorWithPaths("Source input paths exist, but are not readable", nonReadable.map(_.canonicalPath))
      throw FileNotFoundException("Invalid source paths provided")
    }
  }

  private def logErrorWithPaths(message: String, paths: Iterable[String]): Unit = {
    val pathsArray = paths.toArray.sorted
    pathsArray.lengthCompare(1) match {
      case cmp if cmp < 0  => // pathsArray is empty, so don't log anything
      case cmp if cmp == 0 => logger.error(s"$message: ${paths.head}")
      case _ =>
        val errorMessage = (message +: pathsArray.map(path => s"- $path")).mkString("\n")
        logger.error(errorMessage)
    }
  }

  /** Constructs an absolute path against rootPath. If the given path is already absolute this path is returned
    * unaltered. Otherwise, "rootPath / path" is returned.
    */
  def toAbsolutePath(path: String, rootPath: String): String = {
    val absolutePath = Paths.get(path) match {
      case p if p.isAbsolute            => p
      case _ if rootPath.endsWith(path) => Paths.get(rootPath)
      case p                            => Paths.get(rootPath, p.toString)
    }
    absolutePath.normalize().toString
  }

  /** Constructs a relative path against rootPath. If the given path is not inside rootPath, path is returned unaltered.
    * Otherwise, the path relative to rootPath is returned.
    */
  def toRelativePath(path: String, rootPath: String): String = {
    if (path.startsWith(rootPath)) {
      val absolutePath = Paths.get(path).toAbsolutePath
      val projectPath  = Paths.get(rootPath).toAbsolutePath
      if (absolutePath.compareTo(projectPath) == 0) {
        absolutePath.getFileName.toString
      } else {
        projectPath.relativize(absolutePath).toString
      }
    } else {
      path
    }
  }

}
