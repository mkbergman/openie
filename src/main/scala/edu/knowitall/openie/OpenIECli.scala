package edu.knowitall.openie

import java.io.File
import java.io.PrintWriter
import scala.io.Source
import resource._
import java.net.URL
import edu.knowitall.tool.parse.DependencyParser
import edu.knowitall.tool.parse.ClearParser
import edu.knowitall.tool.parse.RemoteDependencyParser
import edu.knowitall.tool.srl.Srl
import edu.knowitall.tool.srl.RemoteSrl
import edu.knowitall.tool.srl.ClearSrl
import java.io.PrintStream
import edu.knowitall.tool.sentence.OpenNlpSentencer
import edu.knowitall.openie.util.SentenceIterator

object OpenIECli extends App {
  object OutputFormat {
    def parse(format: String): OutputFormat = format match {
      case "simple" => SimpleFormat
      case "column" => ColumnFormat
      case _ => throw new MatchError("Unknown format: " + format)
    }
  }
  sealed abstract class OutputFormat {
    def print(sentence: String, insts: Seq[Instance])
  }
  case object SimpleFormat extends OutputFormat {
    def print(sentence: String, insts: Seq[Instance]) {
      println(sentence)
      insts foreach println
      println()
    }
  }
  case object ColumnFormat extends OutputFormat {
    def print(sentence: String, insts: Seq[Instance]) {
      insts.foreach { inst =>
        println(
            Iterator(
                inst.confidence,
                inst.extr.context.getOrElse(""),
                inst.extr.arg1,
                inst.extr.rel,
                inst.extr.arg2s.mkString("; "),
                sentence
            ).mkString("\t"))
      }
    }
  }

  case class Config(inputFile: Option[File] = None,
    outputFile: Option[File] = None,
    parserServer: Option[URL] = None,
    srlServer: Option[URL] = None,
    encoding: String = "UTF-8",
    formatter: OutputFormat = SimpleFormat,
    split: Boolean = false) {
    def source() = {
      inputFile match {
        case Some(file) => Source.fromFile(file, encoding)
        case None => Source.fromInputStream(System.in, encoding)
      }
    }

    def writer() = {
      outputFile match {
        case Some(file) => new PrintWriter(file, encoding)
        case None => new PrintWriter(new PrintStream(System.out, true, encoding))
      }
    }

    def createParser(): DependencyParser = parserServer match {
      case Some(url) => new RemoteDependencyParser(url.toString)
      case None => new ClearParser()
    }

    def createSrl(): Srl = srlServer match {
      case Some(url) => new RemoteSrl(url.toString)
      case None => new ClearSrl()
    }
  }

  val argumentParser = new scopt.immutable.OptionParser[Config]("openie") {
    def options = Seq(
      argOpt("input file", "input file") { (string, config) =>
        val file = new File(string)
        require(file.exists, "input file does not exist: " + file)
        config.copy(inputFile = Some(file))
      },
      argOpt("ouput file", "output file") { (string, config) =>
        val file = new File(string)
        config.copy(outputFile = Some(file))
      },
      opt("parser-server", "Parser server") { (string, config) =>
        config.copy(parserServer = Some(new URL(string)))
      },
      opt("srl-server", "SRL server") { (string, config) =>
        config.copy(srlServer = Some(new URL(string)))
      },
      opt("encoding", "Character encoding") { (string, config) =>
        config.copy(encoding = string)
      },
      opt("format", "Output format") { (string, config) =>
        config.copy(formatter = OutputFormat.parse(string))
      },
      flag("s", "split", "Split paragraphs into sentences") { config =>
        config.copy(split = true)
      })
  }

  argumentParser.parse(args, Config()) match {
    case Some(config) => run(config)
    case None =>
  }

  def run(config: Config) {
    val openie = new OpenIE(parser=config.createParser(), srl=config.createSrl())

    lazy val sentencer = new OpenNlpSentencer

    for {
      source <- managed(config.source())
      writer <- managed(config.writer())

      sentences =
        if (config.split) new SentenceIterator(sentencer, source.getLines.buffered)
        else source.getLines

      sentence <- sentences
      if !sentence.trim.isEmpty
    } {
      val insts = openie.extract(sentence)
      config.formatter.print(sentence, insts)
    }
  }
}