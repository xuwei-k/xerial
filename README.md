Xerial Project
===========

The ulitimate goal of Xerial project is to manage everything as database. 

# Modules

## xerial-core
Core utilities of xerial projects.
 
 * Useful collection classes
     * CyclicArray, RedBlackTree, balanced PrioritySearchTree, UnionFindSet etc.
 * Loggger whose log levels and output targets can be configured through a JMX interface at runtime
     * For use, simply extends Logging trait
 * StopWatch for taking benchmarks of codes
 * Resource trait for reading files in classpaths and jar files. 
    * Quite useful for writing codes that need to use resource files. (e.g., test codes, graphic data, font files, etc.)
 * Fast PEG parser generator 
  

## xerial-lens
Retrives object type information using Scala's type signature, which is embeded in class files at the compile time by Scala Compiler.

 * ObjectSchema for getting full-fledged type information including generic types. 
    * Now you are free-from type erasure problem!
 * Eq trait for injecting field-value based hashCode and equals method to any objects
 * Command-line paraser 
   * You can call methods in a class by mapping command line arguments to the method arguments
   * String values are automatically converted to appropriate data types according to the information obtained by ObjectSchema

# Release plan

 * Version 3.0: Scala-based release. 
  * Migrating common utilities from Java to Scala

## Usage
Add the following settings to your sbt build file (e.g., `project/build.sbt`)

    resolvers += "xerial snapshot"  at "http://maven.xerial.org/repository/snapshot/"
    
    libraryDependencies += "org.xerial" % "xerial-core" % "3.0-SNAPSHOT"
    
    # When you want to use ObjectSchema
    libraryDependencies += "org.xerial" % "xerial-lens" % "3.0-SNAPSHOT"

