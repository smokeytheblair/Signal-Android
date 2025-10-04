package org.thoughtcrime.securesms.crash

import android.app.Application
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.util.RemoteConfig
import org.whispersystems.signalservice.api.push.ServiceId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class CrashConfigTest {
  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  @Before
  fun setup() {
    mockkObject(RemoteConfig)

    mockkObject(SignalStore)
    every { SignalStore.account.aci } returns ServiceId.ACI.from(UUID.randomUUID())
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `simple name pattern`() {
    every { RemoteConfig.crashPromptConfig } returns """[ { "name": "test", "percent": 100 } ]"""
    assertThat(CrashConfig.computePatterns()).containsExactly(CrashConfig.CrashPattern(namePattern = "test"))
  }

  @Test
  fun `simple message pattern`() {
    every { RemoteConfig.crashPromptConfig } returns """[ { "message": "test", "percent": 100 } ]"""
    assertThat(CrashConfig.computePatterns()).containsExactly(CrashConfig.CrashPattern(messagePattern = "test"))
  }

  @Test
  fun `simple stackTrace pattern`() {
    every { RemoteConfig.crashPromptConfig } returns """[ { "stackTrace": "test", "percent": 100 } ]"""
    assertThat(CrashConfig.computePatterns()).containsExactly(CrashConfig.CrashPattern(stackTracePattern = "test"))
  }

  @Test
  fun `all fields set`() {
    every { RemoteConfig.crashPromptConfig } returns """[ { "name": "test1", "message": "test2", "stackTrace": "test3", "percent": 100 } ]"""
    assertThat(CrashConfig.computePatterns())
      .containsExactly(CrashConfig.CrashPattern(namePattern = "test1", messagePattern = "test2", stackTracePattern = "test3"))
  }

  @Test
  fun `multiple configs`() {
    every { RemoteConfig.crashPromptConfig } returns
      """
      [ 
        { "name": "test1", "percent": 100 },
        { "message": "test2", "percent": 100 },
        { "stackTrace": "test3", "percent": 100 }
      ]
      """

    assertThat(CrashConfig.computePatterns()).containsExactly(
      CrashConfig.CrashPattern(namePattern = "test1"),
      CrashConfig.CrashPattern(messagePattern = "test2"),
      CrashConfig.CrashPattern(stackTracePattern = "test3")
    )
  }

  @Test
  fun `empty fields are considered null`() {
    every { RemoteConfig.crashPromptConfig } returns
      """
      [ 
        { "name": "", "percent": 100 },
        { "name": "test1", "message": "", "percent": 100 },
        { "message": "test2", "stackTrace": "", "percent": 100 }
      ]
      """

    assertThat(CrashConfig.computePatterns()).containsExactly(
      CrashConfig.CrashPattern(namePattern = "test1"),
      CrashConfig.CrashPattern(messagePattern = "test2")
    )
  }

  @Test
  fun `ignore zero percent`() {
    every { RemoteConfig.crashPromptConfig } returns """[ { "name": "test", "percent": 0 } ]"""
    assertThat(CrashConfig.computePatterns()).isEmpty()
  }

  @Test
  fun `not setting percent is the same as zero percent`() {
    every { RemoteConfig.crashPromptConfig } returns """[ { "name": "test" } ]"""
    assertThat(CrashConfig.computePatterns()).isEmpty()
  }

  @Test
  fun `ignore configs without a pattern`() {
    every { RemoteConfig.crashPromptConfig } returns """[ { "percent": 100 } ]"""
    assertThat(CrashConfig.computePatterns()).isEmpty()
  }

  @Test
  fun `ignore invalid json`() {
    every { RemoteConfig.crashPromptConfig } returns "asdf"
    assertThat(CrashConfig.computePatterns()).isEmpty()
  }

  @Test
  fun `ignore empty json`() {
    every { RemoteConfig.crashPromptConfig } returns ""
    assertThat(CrashConfig.computePatterns()).isEmpty()
  }
}
