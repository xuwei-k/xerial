package xerial.core.lens

/*
 * Copyright 2012 Taro L. Saito
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import collection.mutable.WeakHashMap
import tools.scalap.scalax.rules.scalasig._
import xerial.core.log.Logging

//--------------------------------------
//
// ObjectSchema.scala
// Since: 2012/01/17 10:05
//
//--------------------------------------

/**
 * Object information extractor
 */
object ObjectSchema extends Logging {

  import java.{lang => jl}


  implicit def toSchema(cl: Class[_]): ObjectSchema = ObjectSchema(cl)

  private val schemaTable = new WeakHashMap[Class[_], ObjectSchema]

  /**
   * Get the object schema of the specified type. This method caches previously created ObjectSchema instances, and
   * second call for the same type object return the cached entry.
   */
  def apply(cl: Class[_]): ObjectSchema = schemaTable.getOrElseUpdate(cl, new ObjectSchema(cl))

  def of[A](implicit m: ClassManifest[A]): ObjectSchema = apply(m.erasure)

  def getSchemaOf(obj: Any): ObjectSchema = apply(obj.getClass)

  private val sigCache = new WeakHashMap[Class[_], Option[ScalaSig]]

  def findSignature(cl: Class[_]): Option[ScalaSig] = {
    def enclosingObject(cl: Class[_]): Option[Class[_]] = {
      val pos = cl.getName.lastIndexOf("$")
      val parentClassName = cl.getName.slice(0, pos)
      try {
        Some(Class.forName(parentClassName))
      }
      catch {
        case e => None
      }
    }


    sigCache.getOrElseUpdate(cl, {
      val sig =
        try {
          trace("Searching for signature of %s", cl.getName)
          val s = ScalaSigParser.parse(cl)
          if (s.isDefined) {
            trace("Found signature of %s", cl.getSimpleName)
          }
          s
        }
        catch {
          // ScalaSigParser throws NPE when noe signature for the class is found
          case _ => None
        }
      sig.orElse(enclosingObject(cl).flatMap(findSignature(_)))
    })
  }

  def findFieldOwner(name: String, baseClass: Class[_]): Option[Class[_]] = {
    def isFieldOwner(cl: Class[_]) = {
      try {
        cl.getDeclaredField(name)
        true
      }
      catch {
        case _ => false
      }
    }

    if (isFieldOwner(baseClass))
      Some(baseClass)
    else {
      val parents = findParentClasses(baseClass)
      parents.foldLeft[Option[Class[_]]](None) {
        (opt, cl) => opt.orElse(findFieldOwner(name, cl))
      }
    }
  }

  private def findConstructor(cl: Class[_], sig: ScalaSig): Option[Constructor] = {

    import scala.tools.scalap.scalax.rules.scalasig

    def isTargetClass(t: scalasig.Type): Boolean = {
      t match {
        case TypeRefType(_, ClassSymbol(sinfo, _), _) => {
          //debug("className = %s, found name = %s", className, sinfo.name)
          sinfo.name == cl.getSimpleName
        }
        case _ => false
      }
    }
    def findConstructorParameters(mt: MethodType, sig: ScalaSig): Array[ConstructorParameter] = {
      val paramSymbols: Seq[MethodSymbol] = mt match {
        case MethodType(_, param: Seq[_]) => param.collect {
          case m: MethodSymbol => m
        }
        case _ => Seq.empty
      }



      val l = for (((name, vt), index) <- toAttribute(paramSymbols, sig, cl).zipWithIndex)
      yield {
        // resolve the actual field owner
        val fieldOwner = findFieldOwner(name, cl)

        if (fieldOwner.isEmpty)
          throw new IllegalStateException("No field owner is found: name:%s, base class:%s".format(name, cl.getSimpleName))
        ConstructorParameter(cl, fieldOwner.get, index, name, vt)
      }
      l.toArray
    }

    val entries = (0 until sig.table.length).map(sig.parseEntry(_))
    entries.collectFirst {
      case m: MethodType if isTargetClass(m.resultType) =>
        Constructor(cl, findConstructorParameters(m, sig))
    }
  }

  private def findConstructor(cl: Class[_]): Option[Constructor] = {
    findSignature(cl).flatMap(sig => findConstructor(cl, sig))
  }

  private def isSystemClass(cl: Class[_]) = {
    if (cl == null)
      true
    else {
      val name = cl.getName
      (name.startsWith("java.") || name == "scala" || name.startsWith("scala."))
    }
  }

  def getParentsByReflection(cl: Class[_]) = {
    trace("get parents of %s by reflecton", cl.getSimpleName)
    val i = if (cl.getInterfaces != null) cl.getInterfaces else Array.empty[Class[_]]
    val p = Seq(cl.getSuperclass) ++ i
    val filtered = p.filterNot(c => isSystemClass(c))
    trace("parents of %s: %s ", cl.getSimpleName, filtered.map(_.getName).mkString(", "))
    filtered
  }

  def findParentClasses(cl: Class[_]): Seq[Class[_]] = getParentsByReflection(cl)

  def findParentSchemas(cl: Class[_]): Seq[ObjectSchema] = {
    findParentClasses(cl).map(ObjectSchema(_))
  }

  private def toAttribute(param: Seq[MethodSymbol], sig: ScalaSig, refCl: Class[_]): Seq[(String, ValueType)] = {
    val paramRefs = param.map(p => (p.name, sig.parseEntry(p.symbolInfo.info)))
    val paramSigs = paramRefs.map {
      case (name: String, t: TypeRefType) => (name, t)
    }

    for ((name, typeSignature) <- paramSigs) yield (name, resolveClass(typeSignature))
  }

  def isOwnedByTargetClass(m: MethodSymbol, cl: Class[_]): Boolean = {
    m.symbolInfo.owner match {
      case ClassSymbol(symbolInfo, _) => symbolInfo.name == cl.getSimpleName
      case _ => false
    }
  }

  private def parseEntries(sig: ScalaSig) = (0 until sig.table.length).map(sig.parseEntry(_))

  def parametersOf(cl: Class[_]): Array[Parameter] = {
    findSignature(cl) match {
      case None => Array.empty
      case Some(sig) => {
        val entries = parseEntries(sig)

        val parents = findParentSchemas(cl)
        val parentParams = parents.flatMap {
          p => p.parameters
        }.collect {
          //case c @ ConstructorParameter(owner, fieldOwner, index, name, valueType) => c
          //case m @ MethodParameter(owner, index, name, valueType) => m
          // Fix actual owner
          case FieldParameter(owner, ref, name, valueType) => {
            val fieldOwner = findFieldOwner(name, cl).getOrElse(cl)
            FieldParameter(fieldOwner, cl, name, valueType)
          }
        }

        val constructorParams = findConstructor(cl, sig) match {
          case None => Seq.empty[ConstructorParameter]
          case Some(cc) => cc.params.toSeq
        }

        def isFieldReader(m: MethodSymbol): Boolean = {
          m.isAccessor && !m.isParamAccessor && !m.isLazy && isOwnedByTargetClass(m, cl) && !m.name.endsWith("_$eq") && m.symbolInfo.privateWithin.isEmpty
        }

        val fieldParams = entries.collect {
          case m: MethodSymbol if isFieldReader(m) => {
            entries(m.symbolInfo.info) match {
              case NullaryMethodType(resultType: TypeRefType) => {
                FieldParameter(cl, cl, m.name, resolveClass(resultType))
              }
            }
          }
        }

        // Aggregate parameters
        val foundParams = (constructorParams.map(_.name) ++ fieldParams.map(_.name)).toSet
        val parentOnlyParams = parentParams.filterNot(p => foundParams.contains(p.name))

        (constructorParams ++ fieldParams ++ parentOnlyParams).toArray
      }
    }
  }

  def methodsOf(cl: Class[_]): Array[Method] = {
    val methods = findSignature(cl) match {
      case None => Array.empty[Method]
      case Some(sig) => {
        val entries = (0 until sig.table.length).map(sig.parseEntry(_))

        def isTargetMethod(m: MethodSymbol): Boolean = {
          // synthetic is used for functions returning default values of method arguments (e.g., ping$default$1)
          m.isMethod && !m.isPrivate && !m.isProtected && !m.isImplicit && !m.isSynthetic && !m.isAccessor && m.name != "<init>" && m.name != "$init$" && isOwnedByTargetClass(m, cl)
        }

        def resolveMethodArgTypes(params: Seq[(String, ValueType)]) = {
          params.map {
            case (name, vt) if TypeUtil.isArray(vt.rawType) => {
              val gt = vt.asInstanceOf[GenericType]
              val t = gt.genericTypes(0)
              val loader = Thread.currentThread.getContextClassLoader
              Class.forName("[L%s;".format(t.rawType.getName), false, loader)
            }
            case (name, vt) => vt.rawType
          }
        }

        val targetMethodSymbol: Seq[(MethodSymbol, Any)] = entries.collect {
          case m: MethodSymbol if isTargetMethod(m) => (m, entries(m.symbolInfo.info))
        }

        val methods = targetMethodSymbol.map {
          s =>
            try {
              s match {
                case (m: MethodSymbol, NullaryMethodType(resultType: TypeRefType)) => {
                  val jMethod = cl.getMethod(m.name)
                  Some(Method(cl, jMethod, m.name, Array.empty[MethodParameter], resolveClass(resultType)))
                }
                case (m: MethodSymbol, MethodType(resultType: TypeRefType, paramSymbols: Seq[_])) => {
                  val params = toAttribute(paramSymbols.asInstanceOf[Seq[MethodSymbol]], sig, cl)
                  val jMethod = cl.getMethod(m.name, resolveMethodArgTypes(params): _*)
                  val mp = for (((name, vt), index) <- params.zipWithIndex) yield MethodParameter(jMethod, index, name, vt)
                  Some(Method(cl, jMethod, m.name, mp.toArray, resolveClass(resultType)))
                }
                case _ => None
              }
            }
            catch {
              case e => {
                warn("error occurred when accessing method %s : %s", s, e)
                None
              }
            }
        }
        methods.collect {
          case Some(x) => x
        }.toArray
      }
    }

    val p = parentMethodsOf(cl)
    trace("parent methods of %s: %s", cl.getSimpleName, p.mkString(", "))
    (methods ++ p).toArray
  }

  def parentMethodsOf(cl: Class[_]) = {
    def resolveImplOwner(m: Method) {
      m.owner
    }
    findParentSchemas(cl).flatMap(_.methods).map {
      m =>
        m
    }
  }

  def resolveClass(typeSignature: TypeRefType): ValueType = {

    val name = typeSignature.symbol.path
    val clazz: Class[_] = {
      name match {
        // Resolve classes of primitive types.
        // This special treatment is necessary because classes of primitive types, classOf[scala.Int] etc. are converted by
        // Scala compiler into Java primitive types (e.g., int, float). So classOf[Int] is represented as classOf[int] internally.
        case "scala.Boolean" => classOf[Boolean]
        case "scala.Byte" => classOf[Byte]
        case "scala.Short" => classOf[Short]
        case "scala.Char" => classOf[Char]
        case "scala.Int" => classOf[Int]
        case "scala.Float" => classOf[Float]
        case "scala.Long" => classOf[Long]
        case "scala.Double" => classOf[Double]
        case "scala.Predef.String" => classOf[String]
        // Map and Set type names are defined in Scala.Predef
        case "scala.Predef.Map" => classOf[Map[_, _]]
        case "scala.Predef.Set" => classOf[Set[_]]
        case "scala.package.Seq" => classOf[Seq[_]]
        case "scala.package.List" => classOf[List[_]]
        case "scala.Any" => classOf[Any]
        case "scala.AnyRef" => classOf[AnyRef]
        case _ =>
          // Find the class using the context class loader
          val loader = Thread.currentThread().getContextClassLoader
          try loader.loadClass(name)
          catch {
            case _ => {
              // When class is defined inside an object, its class name has suffix '$' like "xerial.silk.SomeTest$A"
              val parent = typeSignature.symbol.parent
              val anotherClassName = "%s$%s".format(if (parent.isDefined) parent.get.path else "", typeSignature.symbol.name)
              loader.loadClass(anotherClassName)
            }
          }
      }
    }


    if (typeSignature.typeArgs.isEmpty) {
      StandardType(clazz)
    }
    else {
      val typeArgs: Seq[ValueType] = typeSignature.typeArgs.map {
        case x: TypeRefType => resolveClass(x)
      }
      GenericType(clazz, typeArgs)
    }
  }

}

/**
 * Contains information of methods, constructor and parameters defined in a class
 * @author leo
 */
class ObjectSchema(val cl: Class[_]) extends Logging {

  if (cl == null)
    throw new NullPointerException("input class is null")

  import ObjectSchema._

  val name: String = cl.getSimpleName
  val fullName: String = cl.getName

  def findSignature: Option[ScalaSig] = ObjectSchema.findSignature(cl)

  lazy val parameters: Array[Parameter] = parametersOf(cl)
  lazy val methods: Array[Method] = methodsOf(cl)

  lazy private val parameterIndex: Map[String, Parameter] = {
    val pair = for (a <- parameters) yield a.name -> a
    pair.toMap
  }

  def getParameter(name: String): Parameter = {
    parameterIndex(name)
  }
  def findParameter(name: String): Option[Parameter] = {
    parameterIndex.get(name)
  }
  def containsParameter(name: String) = parameterIndex.contains(name)

  lazy val constructor: Constructor = {
    findConstructor(cl) match {
      case Some(c) => c
      case None => throw new IllegalArgumentException("no constructor is found for " + cl)
    }
  }

  override def toString = {
    if (parameters.isEmpty)
      name
    else
      "%s(%s)".format(name, parameters.mkString(", "))
  }
}

