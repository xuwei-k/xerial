package xerial.cui

//--------------------------------------
//
// OptionParser.scala
// Since: 2012/07/06 4:05 PM
//
//--------------------------------------

import xerial.lens._
import util.matching.Regex
import util.matching.Regex.Match
import collection.mutable.ArrayBuffer
import xerial.core.util.{CommandLineTokenizer, StringTemplate}
import xerial.core.log.Logger


/**
 * Creates option parsers
 */
object OptionParser extends Logger {

  def tokenize(line: String): Array[String] = CommandLineTokenizer.tokenize(line)

  def of[A](implicit m: ClassManifest[A]): OptionParser = {
    apply(m.erasure)
  }

  def apply(cl: Class[_]): OptionParser = {
    val schema = ClassOptionSchema(cl)
    assert(schema != null)
    new OptionParser(schema)
  }

  def newParser[A <: AnyRef](optionHolder: A) = {
    val cl = optionHolder.getClass
    new OptionParser(ClassOptionSchema(cl))
  }
  
  def parse[A <: AnyRef](args: Array[String])(implicit m: ClassManifest[A]): OptionParserResult = {
    of[A].parse(args)
  }

  def parse[A <: AnyRef](argLine: String)(implicit m: ClassManifest[A]): OptionParserResult = {
    parse(tokenize(argLine))
  }

  val defaultUsageTemplate = """|usage:$COMMAND$ $ARGUMENT_LIST$
                                |$DESCRIPTION$
                                |$OPTION_LIST$""".stripMargin

  /**
   * Option -> value mapping result
   */
  sealed abstract class OptionMapping extends Iterable[(Path, String)]
  case class OptSetFlag(opt: CLOption) extends OptionMapping{
    def iterator = Iterator.single(opt.path -> "true")
  }
  case class OptMapping(opt: CLOption, value: String) extends OptionMapping{
    def iterator = Iterator.single(opt.path -> value)
  }
  case class OptMappingMultiple(opt: CLOption, value: Array[String]) extends OptionMapping{
    def iterator = value.map(opt.path -> _).iterator
  }
  case class ArgMapping(opt: CLArgItem, value: String) extends OptionMapping{
    def iterator = Iterator.single(opt.path -> value)
  }
  case class ArgMappingMultiple(opt: CLArgument, value: Array[String]) extends OptionMapping{
    def iterator = value.map(opt.path -> _).iterator
  }


}

/**
 * command-line option
 */
sealed trait CLOptionItem {
  def path : Path
  def takesArgument: Boolean = false
  def takesMultipleArguments: Boolean = false
}

abstract class CLOptionItemBase(val param:Parameter) extends CLOptionItem {
  override def takesMultipleArguments: Boolean = {
    import TypeUtil._
    val t: Class[_] = param.valueType.rawType
    isArray(t) || isSeq(t)
  }
}

trait CLArgItem extends CLOptionItem {
  def path: Path
  def name: String
  def argIndex: Int
}

/**
 * CommandTrait line option and the associated class parameter
 * @param annot
 * @param param
 */
case class CLOption(val path:Path, val annot: option, override val param: Parameter) extends CLOptionItemBase(param) {

  // validate prefixes
  val prefixes : Seq[String] =
      for(p <- annot.prefix.split(",")) yield {
        if(p.startsWith("--") || p.startsWith("-"))
          p
        else
          throw new IllegalArgumentException("Invalid prefix %s (not beginning with - or --). Fix option of %s".format(p, param.name))
      }

  override def takesArgument: Boolean = !param.valueType.isBooleanType
}

/**
 * CommandTrait line argument type and the associated class parameter
 * @param arg
 * @param param
 */
case class CLArgument(val path:Path, arg: argument, override val param: Parameter) extends CLOptionItemBase(param) with CLArgItem {
  def name: String =
    if(arg.name.isEmpty)
      param.name
    else
      arg.name

  def argIndex = arg.index
}

case class CommandNameArgument(val path:Path) extends CLArgItem {
  def argIndex = 0
  def name = "commandName"
}

/**
 * Schema of the command line options
 */
trait OptionSchema extends Logger {

  val options: Array[CLOption]
  val args: Array[CLArgItem] // must be sorted by arg.index in ascending order

  protected lazy val symbolTable = {
    var h = Map[String, CLOption]()
    options.foreach {
      case opt: CLOption =>
        for(p <- opt.prefixes)
          h += p -> opt
    }
    h
  }

  def apply(name: String): CLOption = symbolTable.apply(name)

  def findOption(name: String): Option[CLOption] = symbolTable.get(name)
  def findFlagOption(name:String) : Option[CLOption] = {
    findOption(name) filterNot (_.takesArgument)
  }
  def findOptionNeedsArg(name:String) : Option[CLOption] = {
    findOption(name) filter (_.takesArgument)
  }

  def findArgumentItem(argIndex: Int): Option[CLArgItem] = {
    if (args.isDefinedAt(argIndex)) Some(args(argIndex)) else None
  }

  def description: String
  def usage: String

  protected def defaultUsage: String = {
    val l = for (a <- args) yield {
      a.name
    }
    l.map("[%s]".format(_)).mkString(" ")
  }

  override def toString = "options:[%s], args:[%s]".format(options.mkString(", "), args.mkString(", "))
}



object ClassOptionSchema {

  /**
   * Create an option schema from a given class definition
   */
  def apply(cl:Class[_], path:Path = Path.current) : ClassOptionSchema = {
    val schema = ObjectSchema(cl)

    val o = Array.newBuilder[CLOption]
    val a = Array.newBuilder[CLArgItem]
    for (c <- schema.findConstructor; p <- c.params) {
      val nextPath = path / p.name
      p.findAnnotationOf[option] match {
        case Some(opt) => o += new CLOption(nextPath, opt, p)
        case None => p.findAnnotationOf[argument] match {
          case Some(arg) => a += new CLArgument(nextPath, arg, p)
          case None => // nested option
            val nested = ClassOptionSchema(p.valueType.rawType, nextPath)
            o ++= nested.options
            a ++= nested.args
        }
      }
    }

    // find command methods
    val commandMethods = for(m <-schema.methods; c <- m.findAnnotationOf[command]) yield m
    if(!commandMethods.isEmpty || Module.isModuleClass(cl)) {
      if(!a.result().isEmpty)
        sys.error("class %s with command methods cannot have @argument in constructor".format(cl))
      else {
        // Add command name argument
        a += new CommandNameArgument(path / "__commandName")
      }
    }

    new ClassOptionSchema(cl, o.result, a.result().sortBy(x => x.argIndex))
  }



}

/**
 * OptionSchema created from a class definition
 * @param cl
 */
class ClassOptionSchema(val cl: Class[_], val options:Array[CLOption], val args:Array[CLArgItem]) extends OptionSchema {

  def description = {
    cl.getDeclaredAnnotations.collectFirst {
      case c: command => c.description
    }.getOrElse("")
  }

  override def usage = {
    cl.getDeclaredAnnotations.collectFirst {
      case c: command if !c.usage.isEmpty => c.usage
    }.getOrElse(defaultUsage)
  }

}

/**
 * OptionSchema created from a method definition
 * @param method
 */
class MethodOptionSchema(method: ObjectMethod) extends OptionSchema {

  val options =
    for (p <- method.params; opt <- p.findAnnotationOf[option]) yield new CLOption(Path(p.name), opt, p)

  val args = {
    val l = for (p <- method.params; arg <- p.findAnnotationOf[argument]) yield (new CLArgument(Path(p.name), arg, p)).asInstanceOf[CLArgItem]
    l.sortBy(x => x.argIndex)
  }

  def description = {
    method.jMethod.getDeclaredAnnotations.collectFirst {
      case c: command => c.description
    }.getOrElse("")
  }

  override def usage = {
    val argLine =
      method.jMethod.getDeclaredAnnotations.collectFirst {
        case c: command if !c.usage.isEmpty => c.usage
      }.getOrElse(defaultUsage)

    "%s %s".format(method.name, argLine)
  }

}





case class OptionParserResult(parseTree: ValueHolder[String], unusedArgument: Array[String], val showHelp:Boolean) extends Logger {
  
  def buildObject[A](cl:Class[A]) : A = {
    val b = ObjectBuilder(cl)
    build(b).build.asInstanceOf[A]
  }

  def buildObjectWithFilter[A](cl:Class[A], filter: String => Boolean) : A = {
    val b = ObjectBuilder(cl)
    trace("build from parse tree: %s", parseTree)
    for((path, value) <- parseTree.dfs if filter(path.last))
      b.set(path, value)
    b.build.asInstanceOf[A]
  }


  def build[B <: GenericBuilder](builder:B) : B = {
    trace("build from parse tree: %s", parseTree)
    for((path, value) <- parseTree.dfs)
      builder.set(path, value)
    builder
  }
  
}

/**
 * CommandTrait-line argument parser
 *
 * @author leo
 */
class OptionParser(val schema: OptionSchema) extends Logger {

  def this(m: ObjectMethod) = this(new MethodOptionSchema(m))
  
  import OptionParser._

  /**
   * Parse the command-line arguments
   * @param args
   * @param exitAfterFirstArgument
   * @return parse result
   */
  def parse(args: Array[String], exitAfterFirstArgument: Boolean = false): OptionParserResult = {

    def findMatch[T](p: Regex, s: String) : Option[Match] = p.findFirstMatchIn(s) 

    def group(m: Match, group: Int): Option[String] = {
      if (m.start(group) != -1) Some(m.group(group)) else None
    }

    case class Flag(opt:CLOption, remaining:List[String])
    case class WithArg(opt:CLOption, v:String, remaining:List[String])

    // case object for pattern matching of options
    object OptionFlag {
      private val pattern = """^(-{1,2}\w+)""".r

      def unapply(s: List[String]): Option[Flag] = {
        findMatch(pattern, s.head) flatMap { m =>
          val symbol = m.group(1)
          schema.findFlagOption(symbol) map { Flag(_, s.tail) }
        }
      }
    }

    // case object for pattern matching of options that take arguments
    object OptionWithArgument {
      private val pattern = """^(-{1,2}\w+)([:=](\w+))?""".r

      def unapply(s: List[String]): Option[WithArg] = {
        findMatch(pattern, s.head) flatMap { m =>
            val symbol = m.group(1)
            val immediateArg = group(m, 3)
            schema.findOptionNeedsArg(symbol) map { opt =>
              if (immediateArg.isEmpty) {
                if (s.tail.isEmpty)
                  throw new IllegalArgumentException("Option %s needs an argument" format opt)
                else {
                  val remaining = s.tail
                  WithArg(opt, remaining.head, remaining.tail)
                }
              }
              else
                WithArg(opt, immediateArg.get, s.tail)
            }
        }
      }
    }


    // Hold mapping, option -> args ...
    val optionValues = collection.mutable.Map[CLOptionItem, ArrayBuffer[String]]()
    val unusedArguments = new ArrayBuffer[String]

    val logger = getLogger("traverse")

    def traverseArg(l: List[String]): Unit = {
      var argIndex = 0

      logger.trace("index:%d, remaining:%s", argIndex, l)

      def appendOptionValue(ci: CLOptionItem, value: String): Unit = {
        val holder = optionValues.getOrElseUpdate(ci, new ArrayBuffer[String]())
        holder += value
      }

      def setArgument(arg: String): Unit = {
        schema.findArgumentItem(argIndex) match {
          case Some(ai) => {
            appendOptionValue(ai, arg)
            if (!ai.takesMultipleArguments)
              argIndex += 1
          }
          case None => unusedArguments += arg
        }
      }

      // Process command line arguments
      var continue = true
      var remaining = l
      while (continue && !remaining.isEmpty) {
        val next = remaining match {
          case OptionFlag(m) => {
            appendOptionValue(m.opt, "true")
            m.remaining
          }
          case OptionWithArgument(m) => {
            appendOptionValue(m.opt, m.v)
            m.remaining
          }
          case e :: rest => {
            setArgument(e)
            if (exitAfterFirstArgument) {
              continue = false
              unusedArguments ++= rest
            }
            rest
          }
          case Nil => List() // end of arguments
        }
        remaining = next
      }
    }

    traverseArg(args.toList)

    val mapping: Seq[OptionMapping] = {
      val m: TraversableOnce[OptionMapping] = for ((opt, values) <- optionValues) yield {
        opt match {
          case c: CLOption =>
            if (c.takesArgument) {
              if (c.takesMultipleArguments)
                OptMappingMultiple(c, values.toArray)
              else
                OptMapping(c, values(0))
            }
            else
              OptSetFlag(c)
          case a: CLArgument =>
            if (a.takesMultipleArguments)
              ArgMappingMultiple(a, values.toArray)
            else
              ArgMapping(a, values(0))
          case cn: CommandNameArgument =>
            ArgMapping(cn, values(0))
        }
      }
      m.toSeq
    }


    val holder = ValueHolder(for (m <- mapping; (p, v) <- m) yield p -> v)
    trace("parse treer: %s", holder)
    val showHelp = mapping.collectFirst{ case c@OptSetFlag(o) if o.annot.isHelp => c }.isDefined
    new OptionParserResult(holder, unusedArguments.toArray, showHelp)
  }

  def printUsage = {
    print(createUsage())
  }

  def createOptionHelpMessage = {
    val optDscr: Array[(CLOption, String)] = for (o <- schema.options)
    yield {
      val opt: option = o.annot
      val hasShort = o.prefixes.exists(_.length==2)
      val hasAlias = o.prefixes.exists(_.length>2)
      val l = new StringBuilder
      l append o.prefixes.mkString(", ")

      if (o.takesArgument) {
        if (hasAlias)
          l append ":"
        else if (hasShort)
          l append " "
        l append "[%s]".format(o.param.name.toUpperCase)
      }
      (o, l.toString)
    }

    val optDscrLenMax =
      if (optDscr.isEmpty) 0
      else optDscr.map(_._2.length).max

    val defaultInstance: Option[_] = {
      try
        schema match {
          case c: ClassOptionSchema => Some(TypeUtil.newInstance(c.cl))
          case _ => None
        }
      catch {
        case _ => None
      }
    }

    def genDescription(opt: CLOption) = {
      //      if (opt.takesArgument) {
      //        if(defaultInstance.isDefined && defaultInstance.get)
      //        "%s (default:%s)".format(opt.annot.description(),
      //      }
      //      else
      opt.annot.description()
    }

    val s = for (x <- optDscr) yield {
      val paddingLen = optDscrLenMax - x._2.length
      val padding = Array.fill(paddingLen)(" ").mkString
      " %s%s  %s".format(x._2, padding, genDescription(x._1))
    }

    val b = new StringBuilder
    if (!s.isEmpty) {
      b.append("[options]\n")
      b.append(s.mkString("\n") + "\n")
    }
    b.result
  }


  def createUsage(template: String = defaultUsageTemplate): String = {
    StringTemplate.eval(template) {
      Map('ARGUMENT_LIST -> schema.usage, 'OPTION_LIST -> createOptionHelpMessage,
        'DESCRIPTION -> schema.description
      )
    }

  }

}
