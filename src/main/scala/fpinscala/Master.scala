import nomo._

import scala.io.Source
import java.io.File
import sys.process._

object Master {
  
  val P = Configurations.stringParsers[Unit]
  implicit val u = ()
  import P._
  import Errors.msg

  val spaces = takeWhile(c => c == ' ' || c == '\t') >> unit(()) 
  val nl = single('\n')
  val line = takeWhile(_ != '\n').map(_.get) << nl
  val blank = line.filter(_.trim.isEmpty, msg("expected blankline")).attempt
  val blanks = blank.many
  val nonblank = line.filter(!_.trim.isEmpty, msg("expected non-blank line")).map(s => if (s.trim == ".") "" else s)
  val nonblanks = nonblank.many1.map(_.mkString("\n"))
  val tline = line.map(_.trim)

  def token[A](p: Parser[A]) = (p << spaces)
  implicit def toParser(s: String) = token(word(s) | fail(msg("expected: '" + s + "'")) >> unit(()))

  // prompt should not include the sys.error("todo"), that will be 
  case class Question(label: String, prompt: Option[String], hints: List[String], answer: String, explanation: Option[String])
  case class Suite(header: Option[String], questions: List[Question], footer: Option[String])
  case class Example(label: String, content: String)
  case class Examples(header: Option[String], get: List[Example], footer: Option[String])
  case class Chapter(label: String, examples: Examples, suites: List[Suite])
  case class Book(chapters: List[Chapter])

  val open = token("#[") | fail(msg("expected '#['"))
  val close = token("]#") | fail(msg("expected ']#'"))
  // todo: implement def untilMatch(s: F): Parser[MonotypicW[F,I]]
  // reads until the given string is a prefix of the remaining input, or until EOF
  // if this never occurs
  def section(keyword: String, trim: Boolean = false): Parser[String] = 
    (spaces >> token(keyword)).attempt >> 
    word("#[") >> (if (trim) takeWhile(_.isWhitespace) else spaces >> blanks) >> 
    takeThrough("]#").map(_.get) << 
    blanks scope (msg(keyword)) 

  def namedSection[A](keyword: String, trim: Boolean = false)(f: String => Parser[A]): Parser[A] = 
    (spaces >> token(keyword)).attempt >> 
    takeUntil("#[").map(_.get.trim).
    filter(!_.isEmpty, msg("expected nonempty section name")).
    flatMap(s => word("#[") >> (if (trim) takeWhile(_.isWhitespace) else spaces >> blanks) >> f(s) << 
      spaces << close) << 
    blanks scope (msg(keyword)) 
 
  val question: Parser[Question] = 
    namedSection("question") { q => 
      section("prompt").optional ++
      section("hint", true).attempt.many ++  
      section("answer") ++
      section("explanation", true).optional map { 
      case p ++ h ++ a ++ e => Question(q,p,h,a,e) }
    }

  val suite: Parser[Suite] = 
    section("header").optional ++
    question.many1 ++
    section("footer").optional map {
    case h ++ a ++ e => Suite(h,a,e)
  } scope (msg("suite")) commit

  val example: Parser[Example] = 
    namedSection("example") { l => takeUntil("]#") map (txt => Example(l,txt.get)) }

  val examples: Parser[Examples] = 
    section("header").optional ++
    example.many ++
    section("footer").optional map {
    case h ++ a ++ e => {
      val r = Examples(h,a,e)
      println(r)
      r
    }
  }
    
  val chapter: Parser[Chapter] = 
    namedSection("chapter") { t => 
      examples ++ 
      suite.many1 map { case es ++ s => Chapter(t,es,s) }
    }

  def include(baseDir: String): Parser[Chapter] = spaces >> "include" >> tline map (
    f => chapter.scope(msg(baseDir+"/"+f))(readFile(baseDir+"/"+f))) mapStatus (
    _.flatMap(_.status))

  def part(baseDir: String): Parser[List[Chapter]] = 
    namedSection("part") { l => include(baseDir).many1 } 

  def book(baseDir: String): Parser[Book] = 
    namedSection("book") { l => part(baseDir).many1.map(cs => Book(cs.flatten)) }
  
  def readFile(file: String): String = 
    Source.fromFile(file).getLines.mkString("\n")

  def write(srcBaseDir: String, includesBaseDir: String, book: Book): Unit = {
    new File(srcBaseDir + "/exercises").mkdirs
    new File(srcBaseDir + "/answers").mkdirs
    new File(srcBaseDir + "/examples").mkdirs
    new File(includesBaseDir + "/includes/examples").mkdirs
    new File(includesBaseDir + "/includes/exercises").mkdirs
    emitHints(includesBaseDir, book) 
    emitBook(srcBaseDir, includesBaseDir, book) 
  }

  def formatSuite(s: Suite, f: Question => String): String =
    s.header.map(_ + "\n").getOrElse("") + 
    s.questions.map(f).mkString("\n") + "\n" + 
    s.footer.map(_ + "\n").getOrElse("")

  def leadingWhitespace(s: String) = s.takeWhile(_.isWhitespace)

  // returns (label, examples, exercises, answers, exercises by name, examples by name)  
  def formatChapter(chapter: Chapter): (String, String, String, String, List[(String,String)], List[(String,String)]) = {
    def formatExercise(q: Question): String = 
      q.prompt.map(p => p + "\n"+leadingWhitespace(p)+"  sys.error(\"todo\")").
      getOrElse("")
    def formatAnswer(q: Question): String =
      q.prompt.map(_ + "\n").getOrElse("") + q.answer
    (chapter.label, 
      chapter.examples.header.getOrElse("") + 
        chapter.examples.get.map(_.content).mkString("\n") + 
        chapter.examples.footer.getOrElse(""),
      chapter.suites.map(formatSuite(_, formatExercise)).mkString("\n"),
      chapter.suites.map(formatSuite(_, formatAnswer)).mkString("\n"),
      chapter.suites.flatMap(s => s.questions.map(q => (q.label, formatExercise(q)))),
      chapter.examples.get.map(s => (s.label,s.content)))
  }

  def emitChapter(srcBaseDir: String, includesBaseDir: String, chapter: Chapter): Unit = {
    def packageDecl(sub: String) = "package fpinscala." + sub + "\n\n"
    val (label, examples, exercises, answers, exercisesByName, examplesByName) = formatChapter(chapter)
    write(packageDecl("examples") + examples, srcBaseDir + "/examples/" + label + ".scala")
    write(packageDecl("exercises") + exercises, srcBaseDir + "/exercises/" + label + ".scala")
    write(packageDecl("answers") + answers, srcBaseDir + "/answers/" + label + ".scala")
    exercisesByName.foreach { case (name,e) => 
      write(e, includesBaseDir + "/includes/exercises/" + name + ".scala") 
    }
    examplesByName.foreach { case (name,e) => 
      write(e, includesBaseDir + "/includes/examples/" + name + ".scala") 
    }
  }

  def emitBook(srcBaseDir: String, includesBaseDir: String, book: Book): Unit = 
    book.chapters.foreach(emitChapter(srcBaseDir,includesBaseDir,_))

  def emitHints(baseDir: String, book: Book): Unit = {
    val hints: List[(Int,List[(List[String], Int)])] = 
      book.chapters.zipWithIndex.map { case (chapter,n) => 
        (n, chapter.suites.flatMap(_.questions.map(_.hints)).zipWithIndex)
      }
    val hintsByQuestion: List[List[String]] = 
      hints.flatMap { case (n,ch) => ch.map { case (hs, ex) => formatHints(n,ex,hs) }}
    val maxLevel = hintsByQuestion.map(_.length).max 
    val hintsByLevel = 
      (0 until maxLevel) map (i => hintsByQuestion.filter(_.length > i).map(_(i)))

    hintsByLevel.zipWithIndex foreach { case (hs,i) => 
      val f = baseDir + "/hints"+i+".markdown" 
      val html = baseDir + "/hints"+i+".html" 
      val header = "% Hints - level " + i + "\n\n"
      write(header + hs.mkString("\n\n"), f)
      "pandoc --table-of-contents -s -f markdown -t html" #< new File(f) #> new File(html) !
    }
  }

  def formatHints(chapter: Int, exercise: Int, hints: List[String]): List[String] = 
  hints.map(h =>
  """
  |### %d.%d ###
  |
  |%s 
  """.stripMargin.format(chapter+1,exercise+1,h))

  def write(content: String, file: String): Unit = {
    print("writing to: " + file)
    val out = new java.io.PrintWriter(new File(file))
    try { out.print(content) }
    finally { out.close; println("...done") }
  }

  def run(file: String, srcBaseDir: String, includesBaseDir: String): Unit = {
    val b = book(new java.io.File(file).getParent).scope(msg(file))(readFile(file)).get
    write(srcBaseDir, includesBaseDir, b)
  }
  
  def main(args: Array[String]): Unit = {
    if (args.length < 1) println("supply a .book file") 
    else if (args.length == 1) run(args(0), "src/main/scala/fpinscala", "src/main/resources")
    else run(args(0), args(1), args(2))
  } 
}
