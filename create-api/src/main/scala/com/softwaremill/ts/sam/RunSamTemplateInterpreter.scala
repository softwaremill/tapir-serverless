package com.softwaremill.ts.sam

import com.softwaremill.ts.TestEndpoints
import io.circe.syntax._
import SamTemplateEncoders._
import TestEndpoints._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object RunSamTemplateInterpreter extends App {
  if (args.length < 2) sys.error("Usage: [app name] [image uri]")

  val namePrefix = args(0)
  val imageUri = args(1)
  val targetFile = if (args.length > 2) Some(args(2)) else None

  val endpoints = List(endpoint1, endpoint2, endpoint3)
  val samTemplate = SamTemplateInterpreter(endpoints, namePrefix, imageUri)
  val yaml = Printer(dropNullKeys = true, preserveOrder = true, stringStyle = Printer.StringStyle.Plain)
    .pretty(samTemplate.asJson)

  targetFile match {
    case Some(path) => Files.write(Paths.get(path), yaml.getBytes(StandardCharsets.UTF_8))
    case None       => println(yaml)
  }
}
