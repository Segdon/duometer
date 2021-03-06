package com.pawelmandera.main

import java.io.{ File, PrintWriter }
import scala.util.Try

import com.pawelmandera.io.{ TextFile, TikaFile, PlainTextFile, Listings }
import com.pawelmandera.hash.{ LongHash, ElementHashes }
import com.pawelmandera.text.Text
import com.pawelmandera.duplicates.{ MinHashDetection, SuperShingleCandidates, SharedMemberCandidates, Sketch }


object MinHashMain {
  /** Configuration object for scopt parser
    *
    * Default parameters for minhash based on:
    * Henzinger, M. (2006). Finding near-duplicate web pages: a large-scale evaluation of algorithms.
    * In Proceedings of the 29th annual international ACM SIGIR conference on Research and
    * development in information retrieval (pp. 284–291). ACM.
    * Retrieved from http://dl.acm.org/citation.cfm?id=1148222
    */
  case class Config(
    inFiles: Vector[File] = Vector(),
    plainText: Boolean = false,
    outFile: File = new File("out"),
    verbose: Boolean = false,
    threshold: Double = 0.2,
    superShingles: Option[Int] = None,
    seed: Int = scala.util.Random.nextInt(),
    ngramSize: Int = 8,
    nHashFunc: Int = 84)

  val defaultConfig = Config()

  /** build scopt commandline parser */
  val parser = new scopt.OptionParser[Config]("duometer") {
    head("duometer",  "0.1.3")
    opt[File]('i', "input") required() maxOccurs(2) action {
        (x, c) => c.copy(inFiles = c.inFiles :+ x)
    } valueName("<file|dir>") text(
      "File listing documents or a directory to look for duplicates " +
      "(if set twice, look for duplicates across two lists/directories)")
    opt[File]('o', "output") required() action {
      (x, c) => c.copy(outFile = x)
    } valueName("<file>") text("Output file")
    opt[Int]('n', "ngram-size") action {
      (x, c) => c.copy(ngramSize = x)
    } valueName("<size>") text(
      s"N-gram size for shingling, default: ${defaultConfig.ngramSize}")
    opt[Int]('f', "hash-func") action {
      (x, c) => c.copy(nHashFunc = x)
    } valueName("<number>") text(
      s"Number of hashing functions in minhash, default: ${defaultConfig.nHashFunc}")
    opt[Int]('r', "random-seed") action {
      (x, c) => c.copy(seed = x)
    } valueName("<seed>") text("Random seed")
    opt[Int]('s', "super-shingles") action {
      (x, c) => c.copy(superShingles = Some(x))
    } valueName("<size>") text(
      "Compare pairs based on common super-shingles of a given size")
    opt[Double]('t', "threshold") action {
      (x, c) => c.copy(threshold = x)
    } valueName("<value>") text(
      s"Similarity threshold for a pair to be listed in the output, default: ${defaultConfig.threshold}")
    opt[Unit]('p', "plain-text") action {
      (_, c) => c.copy(plainText = true) } text(
        "The files contain plain-text only.")
    opt[Unit]("verbose") action {
      (_, c) => c.copy(verbose = true) } text(
        "Print extra information during processing")
    help("help") text("Print this usage text")
    version("version") text("Print version")
  }

  case class NgramTextFile(n: Int, tf: TextFile)

  def getFiles(source: File, ngramSize: Int, plainText: Boolean = false): Set[NgramTextFile] = {
    val paths = Listings.listPath(source).getOrElse {
      throw new Exception(s"Cannot get files from $source.")
    }

    val textFiles: Set[TextFile] = paths.toSet map { e: String => if (plainText) PlainTextFile(e) else TikaFile(e) }

    textFiles map { NgramTextFile(ngramSize, _) }
  }

  def main(args: Array[String]) {
    parser.parse(args, Config()) match {
      case None => println("Could not parse arguments.")
      case Some(config) => {
        val elemsA = getFiles(config.inFiles(0), config.ngramSize, config.plainText)
        val elemsB = if (config.inFiles.length == 2) { getFiles(config.inFiles(1), config.ngramSize, config.plainText) }
                     else { elemsA }

        val elemToId = (elemsA ++ elemsB).toList.zipWithIndex.toMap
        val idToElem = elemToId map { _.swap }

        val elemsAIds = (elemsA map { elemToId(_) })
        val elemsBIds = (elemsB map { elemToId(_) })

        implicit object IndexedElementHashes extends ElementHashes[Int] {
          def tokenizer: Text.SentenceTokenizer = Text.defaultTokenizeSentences

          def hashes(id: Int): Try[Set[Long]] = {
            val x = idToElem(id)
            if (config.verbose) {
              println(x.tf.path)
            }

            val ngramsTry: Try[TraversableOnce[Seq[String]]] = x.tf.ngrams(x.n)(tokenizer)

            if (ngramsTry.isFailure) System.err.println(s"Error when processing: ${x.tf.path}")

            for { ngrams <- ngramsTry } yield (ngrams map { ngram => LongHash.ngramHash(ngram) }).toSet
          }
        }

        val minHash = config.superShingles match {
          case None => new MinHashDetection with SharedMemberCandidates
          case Some(x) => new MinHashDetection with SuperShingleCandidates { val size = x }
        }

        val simPairs: Iterator[(Int, Int, Double)] =
          minHash.detect(elemsAIds, elemsBIds, config.nHashFunc, config.seed)

        val duplicates = simPairs filter { _._3 >= config.threshold }

        def outFormat(e: (Int, Int, Double)): String =
          idToElem(e._1).tf.path + "\t" + idToElem(e._2).tf.path + "\t" + e._3

        val writer = new PrintWriter(config.outFile)

        try {
          duplicates foreach { e => writer.println(outFormat(e)) }
        } finally {
          writer.close()
        }
      }
    }
  }
}
