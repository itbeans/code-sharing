package com.sada.bytes

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

class HTMLEditor {

  private case class UserInput(filename: String, tagName: String, newContent: String) {
    def apply(): Unit = collectUserInput()
  }

  private def collectUserInput(): UserInput = {
    println("Enter the HTML file path:")
    val filename = scala.io.StdIn.readLine()
    println("Enter the tag name to replace:")
    val tagName = scala.io.StdIn.readLine()
    println("Enter the new content for the tag:")
    val newContent = scala.io.StdIn.readLine()

    UserInput(filename, tagName, newContent)

  }

  def editFile(mode: String): Unit = {
    val userInput = collectUserInput()
    replaceTagContent(userInput.filename, userInput.tagName, userInput.newContent, mode)
  }
  /**
   * Reads an HTML file and replaces the content of a specified tag with new content.
   *
   * @param filename  The path to the HTML file.
   * @param tagName   The name of the tag whose content needs to be replaced.
   * @param newContent The new content to replace the existing content within the specified tag.
   */
  private def replaceTagContent(filename: String, tagName: String, newContent: String, mode: String): Unit = {
    val filepath = Paths.get(filename)
    val outFilepath = Paths.get(filename + ".EDITED")
    val writer = Files.newBufferedWriter(outFilepath)
    Files.readAllLines(filepath).asScala.map(line => {
      val updatedLine = replaceContent(tagName, line, newContent)
      //println(updatedLine)
      updatedLine
    }).foreach(line => {
      println(line)
      if (mode == "PROD") {println("WRITING"); writer.write("" + line + "\n")}
      else println(s"ERROR Writing: $line")
    })
    writer.close()

  }

  private def replaceContent(tagName: String, content: String, newContent: String): String = {
    val tagPattern = s"<$tagName>(.*?)</$tagName>".r
    tagPattern.replaceAllIn(content, s"<$tagName>$newContent</$tagName>")
  }

}