@file:Suppress("UnstableApiUsage")

import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.accessors.dm.LibrariesForTestLibs
import org.gradle.api.JavaVersion
import org.gradle.kotlin.dsl.extra

val libs = the<LibrariesForLibs>()
val testLibs = the<LibrariesForTestLibs>()

val signalBuildToolsVersion: String by rootProject.extra
val signalCompileSdkVersion: String by rootProject.extra
val signalTargetSdkVersion: Int by rootProject.extra
val signalMinSdkVersion: Int by rootProject.extra
val signalJavaVersion: JavaVersion by rootProject.extra
val signalKotlinJvmTarget: String by rootProject.extra

plugins {
  // We cannot use the version catalog in the plugins block in convention plugins (it's not supported).
  // Instead, plugin versions are controlled through the dependencies block in the build.gradle.kts.
  id("com.android.library")
  id("kotlin-android")
  id("ktlint")
}

android {
  buildToolsVersion = signalBuildToolsVersion
  compileSdkVersion = signalCompileSdkVersion

  defaultConfig {
    minSdk = signalMinSdkVersion
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = signalJavaVersion
    targetCompatibility = signalJavaVersion
  }

  kotlinOptions {
    jvmTarget = signalKotlinJvmTarget
    suppressWarnings = true
  }

  lint {
    disable += "InvalidVectorPath"
  }
}

dependencies {
  lintChecks(project(":lintchecks"))

  coreLibraryDesugaring(libs.android.tools.desugar)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(libs.rxjava3.rxandroid)
  implementation(libs.rxjava3.rxjava)
  implementation(libs.rxjava3.rxkotlin)
  implementation(libs.kotlin.stdlib.jdk8)

  ktlintRuleset(libs.ktlint.twitter.compose)

  testImplementation(testLibs.junit.junit)
  testImplementation(testLibs.robolectric.robolectric)
  testImplementation(testLibs.androidx.test.core)
  testImplementation(testLibs.androidx.test.core.ktx)
}
