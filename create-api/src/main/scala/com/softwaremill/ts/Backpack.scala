package com.softwaremill.ts

object Backpack extends App {
  val input = """4 3
                |1 1
                |1 1
                |3 3
                |1 1
                |1 1""".stripMargin

  val lines = input.split("\n").toList
  val (n, m) = {
    val x = lines.head.split(" ").toList
    (x.head.toInt, x(1).toInt)
  }
  val items = lines.tail.map { l =>
    val x = l.split(" ").toList
    (x.head.toInt, x(1).toInt)
  }

  println(n)
  println(m)
  println(items)
}
