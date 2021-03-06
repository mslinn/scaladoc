package com.micronautics.publish

import java.io.File
import java.nio.file.{Path, Paths}
import org.apache.commons.io.FileUtils
import scala.collection.mutable

/** @see See [[https://stackoverflow.com/a/31322970/553865 How to link classes from JDK into scaladoc-generated doc?]] */
object Scaladoc {
  import scala.util.matching.Regex
  import java.nio.charset.Charset
  import scala.util.matching.Regex.Match

  lazy val Array(javaVerPrefix, javaVerMajor, javaVerMinor, _, _) =
    System.getProperty("java.runtime.version").split("\\.|_|-b")

  lazy val javaApiUrl: String = s"https://docs.oracle.com/javase/$javaVerMajor/docs/api/index.html"

  lazy val externalJavadocMap: mutable.Map[String, String] = mutable.Map(
    "owlapi" -> "http://owlcs.github.io/owlapi/apidocs_4_0_2/index.html"
  )

  lazy val fixJavaLinks: Match => String =
    m => m.group(1) + "?" + m.group(2).replace(".", "/") + ".html"

  lazy val allExternalJavadocLinks: Seq[String] = javaApiUrl +: externalJavadocMap.values.toSeq

  /** `rt.jar` is located in the path stored in the `sun.boot.class.path` system property.
   * @see See [[https://docs.oracle.com/javase/8/docs/technotes/tools/findingclasses.html the Oracle documentation]]. */
  lazy val rtJar: String =
    System
      .getProperty("sun.boot.class.path")
      .split(File.pathSeparator)
      .collectFirst {
        case str: String if str.endsWith(File.separator + "rt.jar") => str
      }.get // fail hard if not found

  lazy val utf8: Charset = Charset.forName("UTF-8")

  /** Fix Java links - replace `#java.io.File` with `?java/io/File.html` */
  def fixJavaDocLinks(target: File, classpath: List[String]): Unit = {
    import scala.collection.JavaConverters._
    import java.net.URL

    // Look up the path to the jar with the given prefix from the classpath
    def findJar(namePrefix: String): File =
      classpath.find { _.matches(s"$namePrefix*.jar") }.map(new File(_)).get

    /** External documentation paths */
    // todo should this info be somehow used when creating scaladoc?
    val enhancedMap: mutable.Map[File, URL] =
      externalJavadocMap.map {
        case (name, javadocURL) => findJar(name) -> new URL(javadocURL)
      } + (new File(rtJar) -> new URL(javaApiUrl))

    // Patch the links to JavaDoc in the generated Scaladoc
    FileUtils
      .listFiles(target, Array("html"), true)
      .asScala
      .filter(hasJavadocLink).foreach { file =>
        val newContent: String = allExternalJavadocLinks.foldLeft(FileUtils.readFileToString(file, utf8)) {
          case (oldContent: String, javadocUrl: String) =>
            javadocLinkRegex(javadocUrl).replaceAllIn(oldContent, fixJavaLinks)
        }
        FileUtils.write(file, newContent, utf8)
      }
  }

  def hasJavadocLink(f: File): Boolean = allExternalJavadocLinks exists {
    javadocUrl: String =>
      (javadocLinkRegex(javadocUrl) findFirstIn FileUtils.readFileToString(f, utf8)).nonEmpty
  }

  def javadocLinkRegex(javadocURL: String): Regex = ("""\"(\Q""" + javadocURL + """\E)#([^"]*)\"""").r
}

/** @param classPath Specify where to find user class files (on Unix-based systems a colon-separated list of paths, on Windows-based systems, a semicolon-separate list  of
  *            paths). This does not override the built-in ("boot") search path.
  *            The default class path is the current directory. Setting the CLASSPATH variable or using the -classpath command-line option overrides that default, so
  *            if you want to include the current directory in the search path, you must include "." in the new settings.
  * @param deprecation Indicate whether source should be compiled with deprecation information; defaults to off (accepted values are: on, off, yes and no)
  * @param diagrams Create inheritance diagrams for classes, traits and packages.
  *                 Ignored if the `dot` program from the `graphviz` package is not installed
  * @param encoding Specify character encoding used by source files.
  *           The default value is platform-specific (Linux: "UTF8", Windows: "Cp1252").
  *           Executing the following code in the Scala interpreter will return the default value on your system:
  *           {{{new java.io.InputStreamReader(System.in).getEncoding}}}
  * @param externalDoc Comma-separated list of classpath_entry_path#doc_URL pairs describing external dependencies.
  *                    Example: `-doc-external-doc:/usr/lib/jvm/java-8-openjdk-amd64/jre/lib/rt.jar#http://docs.oracle.com/javase/8/docs/api/`
  * @param footer  A footer on every Scaladoc page, by default set to non-blank space. Can be overridden with a custom footer.
  * @param implicits Document members inherited by implicit conversions.
  * @param sourcePath Location(s) of source files.
  * @param sourceUrl A URL pattern used to link to the source file; the following variables are available:
  *                  €{TPL_NAME}, €{TPL_OWNER} and respectively €{FILE_PATH}.
  *                  For example, for `scala.collection.Seq`, the variables will be expanded to `Seq`, `scala.collection`
  *                  and respectively `scala/collection/Seq`.
  *                  To obtain a relative path for €{FILE_PATH} instead of an absolute path, use the `-sourcepath` setting.
  * @param outputDirectory directory to generate documentation into.
  * @param title overall title of the documentation, typically the name of the library being documented.
  * @param verbose Output messages about what the compiler is doing
  * @param version An optional version number to be appended to the title.
  */
case class Scaladoc(
  classPath: String = ".", // is this the best default value?
  deprecation: Boolean = true,
  diagrams: Boolean = true,
  encoding: String = "",
  externalDoc: String = "",
  footer: String = "&nbsp;",
  implicits: Boolean = true,
  outputDirectory: Path = Paths.get(""),
  sourcePath: String = ".",
  sourceUrl: String = "",
  title: String = "",
  verbose: Boolean = false,
  version: String = ""
)(implicit subProject: SubProject) {
  import Documenter._

  @inline protected def option(name: String, value: String): List[String] =
    if (value.nonEmpty) List(name, value) else Nil

  @inline protected def option(name: String, value: Boolean): List[String] =
    if (value) List(name) else Nil

  def run(cwd: File, commandLine: CommandLine): String = {
    outputDirectory.toFile.mkdirs()

    val dotIsInstalled = commandLine.which("dot").isDefined
    if (diagrams && !dotIsInstalled)
      log.warn("""Inheritance diagrams were requested, but the 'dot' program from the 'graphviz' package is not installed.
                 |Please see http://www.graphviz.org/Download.php
                 |""".stripMargin)

    val options =
      option("-classpath",        classPath) :::
      option("-d",                outputDirectory.toString) :::
      option("-deprecation",      deprecation) :::
      option("-diagrams",         diagrams && dotIsInstalled) :::
      option("-doc-external-doc", externalDoc) :::
      option("-doc-footer",       footer) :::
      option("-doc-source-url",   sourceUrl) :::
      option("-doc-title",        title) :::
      option("-doc-version",      version) :::
      option("-implicits",        implicits) :::
      option("-sourcepath",       sourcePath) ::: // todo this seems to have no effect
      option("-verbose",          verbose)

    val sourceFiles: List[String] = scalaFilesUnder(subProject.srcDir)
    val command = "scaladoc" :: options ::: sourceFiles
    commandLine.run(cwd, command: _*)
  }

  /** @return relativized list of scala file names under `sourcePath`, including `sourcePath`; filters out any files in `sourcePath/src/test` */
  def scalaFilesUnder(sourcePath: File): List[String] = {
    import scala.collection.JavaConverters._
    import org.apache.commons.io.FileUtils.listFiles

    if (!sourcePath.exists) {
      Console.err.println(s"Error: $sourcePath does not exist.")
      System.exit(-1)
    }
    log.debug(s"listing files in $sourcePath, ignoring those in ")
    /*val allSrcFiles = */listFiles(sourcePath, Array("scala"), true)
      .asScala
      .filterNot(_.getPath.startsWith(s"$sourcePath/src/test"))
      .map(_.getPath)
      .toList
     //.map(x => sourcePath.toPath.relativize(x.toPath).toString)
  }
}
