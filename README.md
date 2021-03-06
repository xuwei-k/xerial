Xerial Project
===========

Xerial is data managment utilties for Scala. 
The ulitimate goal of Xerial project is to manage everything as database,
including class objects, text format data (json, XML, Silk, etc.), data
streams, etc.

# Modules

## xerial-core
Core utilities of xerial projects. No dependencies other than the
scala-library exists in xerial-core.
 
 * Useful collection classes
     * CyclicArray (double-ended queue), RedBlackTree, balanced PrioritySearchTree (*O(log N+k)* for interval-intersection queries), UnionFindSet etc.
 * Logger whose log levels and output targets can be configured through a JMX interface at runtime
     * For use, simply extend `xerial.core.log.Logger` trait in your class, then call trace, debug, info, warn, error, fatal methods to output logs.
     * Global log levels can be configured through JVM argument (e.g, -Dloglevel=debug) 
 * Better benchmarking with Timer trait
     * Extend `xerial.core.util.Timer` trait, then wrap your code with `time`
 method. The execution time of the wrapped code will be reported (in debug log)
     * You can also divide your code into sub blocks with `block` method.
     * Repetitive execution is supported; Use `time(repeat=(Int))` or `block(repeat=(Int))`.
 * Resource trait for reading files in classpaths and jar files. 
    * Quite useful for reading resource files. (e.g., test data, graphic data, font files, etc.)
 * Fast PEG parser generator
    * (on-going) Producing [Silk format](http://xerial.org/silk) parser codes for serval programming language including Scala(Java), C, etc.
  
## xerial-lens
Retrives object type information embeded in Scala-generated class files. 

 * ObjectSchema for getting full-fledged type information including generic types. 
    * Now you are free-from the type erasure problem!
    * Use `ObjectSchema(cl:Class[_])` to obtain consturctors, methods and the other parameters defined in a class.  
    * SigParser of the scalap is used for reading ScalaSignatures.
 * `xerial.lens.cui.Launcher` command line parser

### Applications of ObjectSchema
 * Eq trait for injecting field-value based hashCode and equals method to any objects
    * Your classes extending Eq trait become ready to use in containers, e.g, Set[K], Map[K, V] etc.  

 * Command-line parser (`xerial.lens.cui.Launcher`)
   * You can call methods in a class by mapping command line arguments to the method arguments
   * String values are automatically converted to appropriate data types according to the information obtained by ObjectSchema

# Release notes
 * 2016-03-04: release 3.5.0
   * Support Scala 2.12.0-M3, 2.11.7, Scala 2.10.6 (except xerial-lens)
 * Version 3.3.8 (Sepmtember 2nd, 2015)
  * Scala 2.11.7 support
 * Version 3.4   (May 7th, 2015)
  * Scala 2.12.0-M1 support
 * Version 3.3.6 (March 6th, 2015)
  * Scala 2.11.6 support. Using Java8 u40 or later is recommended because u20 has a bug that affects scalap behaviour.
 * Version 3.3.5 (Feb 14th, 2015)
  * Scala 2.11.5 support
 * Version 3.3.0 (June 13th, 2014)
  * Supporting Scala 2.11.1

 * Version 3.2.3 (Jan 4th, 2013)
  * Upgrade to snappy-java-1.1.0.1
  * Fix a bug in DataUnit
 * Version 3.2.2
  * Upgrace to Scala 2.10.3, snappy-java-1.1.0
 * Version 3.2.1
  * Upgrade to Scala 2.10.2, sbt 0.13.0

 * Version 3.2.0
  * Upgrade to Scala 2.10.1
  * Logger interface has changed. Use string-interpolation of Scala 2.10 instead of logging methods that take varialbe-length arguments.
 * Version 3.1.1
  * Timer trait now reports core average of code blocks

 * Version 3.1
  * Scala2.10 support
  * Enhancement of command-line parser: allow nested command line options 
 
 * Version 3.0: Scala-based release. 
  * Migrating common utilities from Java to Scala

## Usage
Add the following settings to your sbt build file (e.g., `build.sbt`)

    libraryDependencies += "org.xerial" %% "xerial-core" % "3.5.0"
    
    # When you want to use ObjectSchema and command line parser
    libraryDependencies += "org.xerial" %% "xerial-lens" % "3.5.0"

#### Using Snapshot version

    resolvers += "Sonatype snapshot repo" at "https://oss.sonatype.org/content/repositories/snapshots/"
    
    libraryDependencies += "org.xerial" % "xerial-core" % "3.5.1-SNAPSHOT"


## Scala API

(Unidoc of API will be prepared)

* [xerial-core 3.3.5 API](https://oss.sonatype.org/service/local/repositories/releases/archive/org/xerial/xerial-core/3.3.5/xerial-core-3.3.5-javadoc.jar/!/index.html)
* [xerial-lens 3.3.5 API](https://oss.sonatype.org/service/local/repositories/releases/archive/org/xerial/xerial-lens/3.3.5/xerial-lens-3.3.5-javadoc.jar/!/index.html)

## For Developers

```
# Cross build
$ ./sbt "so test"

# Release 
$ ./sbt release
```
