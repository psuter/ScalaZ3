import sbt._
import Keys._

object ScalaZ3build extends Build {

  val natives = List("z3.Z3Wrapper")

  lazy val PS               = java.io.File.pathSeparator
  lazy val DS               = java.io.File.separator

  lazy val cPath            = file("src") / "c"
  lazy val cFiles           = file("src") / "c" * "*.c"
  lazy val soName           = System.mapLibraryName("scalaz3")
  lazy val msName           = System.mapLibraryName("msvcr120d")

  lazy val z3Name     = if (isMac) "libz3.dylib" else if(isWindows) "libz3.dll" else System.mapLibraryName("z3")

  lazy val libBinPath       = file("lib-bin")
  lazy val z3BinFilePath    = z3LibPath / z3Name
  lazy val libBinFilePath   = libBinPath / soName
  lazy val msFilePath       = libBinPath / msName
  lazy val jdkIncludePath   = file(System.getProperty("java.home")) / ".." / "include"
  lazy val jdkUnixIncludePath = jdkIncludePath / "linux"
  lazy val jdkWinIncludePath  = jdkIncludePath / "win32"

  lazy val osInf: String = Option(System.getProperty("os.name")).getOrElse("")

  lazy val osArch: String = {
    Option(System.getProperty("sun.arch.data.model"))
      .orElse(Option(System.getProperty("os.arch")))
      .getOrElse("N/A")
  }

  lazy val is64b = osArch.indexOf("64") >= 0

  lazy val isUnix    = osInf.indexOf("nix") >= 0 || osInf.indexOf("nux") >= 0
  lazy val isWindows = osInf.indexOf("Win") >= 0
  lazy val isMac     = osInf.indexOf("Mac") >= 0

  val z3Version = "4.3"

  lazy val z3Dir = {
    val arch = if (is64b) "64b" else "32b"
    val os   = if (isUnix) "unix" else if (isMac) "osx" else if (isWindows) "win" else "NA"

    z3Version+"-"+os+"-"+arch
  }

  lazy val z3IncludePath = {
    file("z3") / z3Dir / "include"
  }

  lazy val z3LibPath = {
    val libString = if (isWindows) "bin" else "lib"

    file("z3") / z3Dir / libString
  }

  def exec(cmd: String, s: TaskStreams) {
    s.log.info("$ "+cmd)
    cmd ! s.log
  }

  val javahKey    = TaskKey[Unit]("javah", "Prepares the JNI headers")
  val gccKey      = TaskKey[Unit]("gcc", "Compiles the C sources")
  val checksumKey = TaskKey[String]("checksum", "Generates checksum file.")

  val checksumTask = (streams, sourceDirectory in Compile) map {
    case (s, sd) =>
      val checksumFilePath = sd / "java" / "z3" / "LibraryChecksum.java"
      val checksumSourcePath = sd / "java" / "z3" / "Z3Wrapper.java"

      import java.io.{File,InputStream,FileInputStream}
      import java.security.MessageDigest

      s.log.info("Generating library checksum")

      val f : File = checksumSourcePath.asFile
      val is : InputStream = new FileInputStream(f)
      val bytes = new Array[Byte](f.length.asInstanceOf[Int])
      var offset : Int = 0
      var read : Int = 0

      while(read >= 0 && offset < bytes.length) {
        read = is.read(bytes, offset, bytes.length - offset)
        if(read >= 0) offset += read
      }
      is.close

      val algo = MessageDigest.getInstance("MD5")
      algo.reset
      algo.update(bytes)
      val digest : Array[Byte] = algo.digest
      val strBuf = new StringBuffer()
      digest.foreach(b => strBuf.append(Integer.toHexString(0xFF & b)))
      val md5String : String = strBuf.toString

      val fw = new java.io.FileWriter(checksumFilePath.asFile)
      val nl = System.getProperty("line.separator")
      fw.write("// THIS FILE IS AUTOMATICALLY GENERATED, DO NOT EDIT" + nl)
      fw.write("package z3;" + nl)
      fw.write(nl)
      fw.write("public final class LibraryChecksum {" + nl)
      fw.write("  public static final String value = \"" + md5String + "\";" + nl)
      fw.write("}" + nl)
      fw.close

      s.log.info("Wrote checksum " + md5String + " as part of " + checksumFilePath.asFile + ".")

      md5String
  }


  val javahTask = (streams, dependencyClasspath in Compile, classDirectory in Compile) map {
    case (s, deps, cd) =>

      deps.map(_.data.absolutePath).find(_.endsWith("lib" + DS + "scala-library.jar")) match {
        case Some(lib) =>
          s.log.info("Preparing JNI headers...")
          exec("javah -classpath " + cd.absolutePath + PS + lib + " -d " + cPath.absolutePath + " " + natives.mkString(" "), s)

        case None =>
          s.log.error("Scala library not found in dependencies ?!?")

      }
  } dependsOn(compile.in(Compile))

  def extractDir(checksum: String): String = {
    System.getProperty("java.io.tmpdir") + DS + "SCALAZ3_" + checksum + DS + "lib-bin" + DS   
  }


  val gccTask = (streams, checksumKey) map { case (s, cs) =>
    s.log.info("Compiling C sources ...")

    // First, we look for z3
    if(!z3IncludePath.isDirectory) {
      sys.error("Could not find Z3 includes: " + z3IncludePath.absolutePath)
    } else if(!z3LibPath.isDirectory) {
      sys.error("Could not find Z3 library: " + z3LibPath.absolutePath)
    } else {
      if (isUnix) {
        exec("gcc -o " + libBinFilePath.absolutePath + " " +
             "-shared -Wl,-soname," + soName + " " +
             "-I" + jdkIncludePath.absolutePath + " " +
             "-I" + jdkUnixIncludePath.absolutePath + " " +
             "-I" + z3IncludePath.absolutePath + " " +
             "-L" + z3LibPath.absolutePath + " " +
             "-Wall " +
             "-g -lc " +
             "-Wl,-rpath,"+extractDir(cs)+" -Wl,--no-as-needed -Wl,--copy-dt-needed " +
             "-lz3 -fPIC -O2 -fopenmp " +
             cFiles.getPaths.mkString(" "), s)

      } else if (isWindows) {
        exec("gcc -shared -o " + libBinFilePath.absolutePath + " " +
             "-D_JNI_IMPLEMENTATION_ -Wl,--kill-at " +
             "-D__int64=\"long long\" " +
             "-I " + "\"" + jdkIncludePath.absolutePath + "\" " +
             "-I " + "\"" + jdkWinIncludePath.absolutePath + "\" " +
             "-I " + "\"" + z3IncludePath.absolutePath + "\" " +
             "-Wreturn-type " +
             cFiles.getPaths.mkString(" ") +
             " " + z3BinFilePath.absolutePath + "\" ", s)
      } else if (isMac) {
        val frameworkPath = "/System/Library/Frameworks/JavaVM.framework/Versions/Current/Headers"

        exec("gcc -o " + libBinFilePath.absolutePath + " " +
             "-dynamiclib" + " " +
             "-I" + jdkIncludePath.absolutePath + " " +
             "-I" + frameworkPath + " " +
             "-I" + z3IncludePath.absolutePath + " " +
             "-L" + z3LibPath.absolutePath + " " +
             "-g -lc " +
             "-Wl,-rpath,"+extractDir(cs)+" " +
             "-lz3 -fPIC -O2 -fopenmp " +
             cFiles.getPaths.mkString(" "), s)
      } else {
        s.log.error("Unknown arch: "+osInf+" - "+osArch)
      }
    }
  }

  val packageTask = (Keys.`package` in Compile).dependsOn(javahKey, gccKey)

  val newMappingsTask = mappings in (Compile, packageBin) <<= (mappings in (Compile, packageBin), streams) map {
    case (normalFiles, s) =>
      val newFiles = 
      (msFilePath.getAbsoluteFile -> ("lib-bin/"+msFilePath.getName)) ::
        (libBinFilePath.getAbsoluteFile -> ("lib-bin/"+libBinFilePath.getName)) ::
        (z3LibPath.listFiles.toList.map { f =>
	  f.getAbsoluteFile -> ("lib-bin/"+f.getName)
        })

      s.log.info("Bundling files:")
      for ((from, to) <- newFiles) {
	  s.log.info(" - "+from+" -> "+to)
      }

      newFiles ++ normalFiles
  }

  val newTestClassPath = internalDependencyClasspath in (Test) <<= (artifactPath in (Compile, packageBin)) map {
    case jar =>
      List(Attributed.blank(jar))
  }

  lazy val root = Project(id = "ScalaZ3",
                          base = file("."),
                          settings = Project.defaultSettings ++ Seq(
                            checksumKey <<= checksumTask,
                            gccKey <<= gccTask,
                            javahKey <<= javahTask,
                            compile.in(Compile) <<= compile.in(Compile).dependsOn(checksumTask),
                            Keys.`package`.in(Compile) <<= packageTask,
                            newTestClassPath,
                            newMappingsTask
                          )
                     )
}
