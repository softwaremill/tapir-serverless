package com.softwaremill.ts.sam

import com.softwaremill.ts.TestEndpoints
import io.circe.syntax._
import SamTemplateEncoders._
import TestEndpoints._

object RunSamTemplateInterpreter extends App {
  val namePrefix = if (args.length > 1) args(1) else "TestApp"
  val imageUri = if (args.length > 2) args(2) else "eks/testApp:latest"

  val es = List(e0, e1, e2)
  val samTemplate = SamTemplateInterpreter(es, namePrefix, imageUri)
  val yaml = Printer(dropNullKeys = true, preserveOrder = true, stringStyle = Printer.StringStyle.Plain)
    .pretty(samTemplate.asJson)

  println(yaml)
}
