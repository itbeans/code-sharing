package com.sada.bytes

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

class HTMLEditor {

  def replaceTagContent(filename: String, tagName: String, newContent: String): Unit = {
    val filepath = Paths.get(filename)
    Files.readAllLines(filepath).asScala.map(line => {
      val updatedLine = replaceContent(tagName, line, newContent)
      //println(updatedLine)
      updatedLine
    }).foreach(println)

  }

  private def replaceContent(tagName: String, content: String, newContent: String): String = {
    val tagPattern = s"<$tagName>(.*?)</$tagName>".r
    tagPattern.replaceAllIn(content, s"<$tagName>$newContent</$tagName>")
  }

}