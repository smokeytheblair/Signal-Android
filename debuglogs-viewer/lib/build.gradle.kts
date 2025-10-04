plugins {
  id("signal-library")
  id("kotlin-parcelize")
}

android {
  namespace = "org.signal.debuglogsviewer"

  buildFeatures {
    buildConfig = true
  }
}

dependencies {
  implementation(project(":core-util"))
  implementation(project(":core-util-jvm"))

  implementation(libs.kotlin.reflect)
  implementation(libs.jackson.module.kotlin)
  implementation(libs.jackson.core)

  testImplementation(testLibs.robolectric.robolectric) {
    exclude(group = "com.google.protobuf", module = "protobuf-java")
  }

  api(libs.google.play.services.wallet)
  api(libs.square.okhttp3)
}
