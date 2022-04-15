@file:Suppress("GradlePackageUpdate", "SpellCheckingInspection")

plugins { kotlin }

dependencies {
  implementationOf(
    "it.unimi.dsi:fastutil:_",
    "net.sf.jopt-simple:jopt-simple:_",
    Libs.Google.Guava,
    Libs.Google.Code.Gson,
    Libs.Ow2.Asm.Analysis,
    Libs.Ow2.Asm.Commons,
    Libs.Ow2.Asm.Tree,
    Libs.Ow2.Asm.Util,
    Libs.KotlinX.Metadata.Jvm,
  )
  api(Libs.Meowool.Toolkit.Sweekt)

  testImplementationOf(
    "junit:junit:_",
    Libs.Kotlin.Reflect,
    // R8 uses a specific version
    Libs.Smali version "2.2b4",
  )
  testImplementationJars()
}

tasks.test {
  // Actually we don't need tests for R8 itself, this should be done by Google upstream
  enabled = false
}