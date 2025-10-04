package org.thoughtcrime.securesms.notifications.profiles

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.keyvalue.NotificationProfileValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.testutil.MockAppDependenciesRule
import org.thoughtcrime.securesms.util.toMillis
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class NotificationProfilesTest {
  @get:Rule
  val appDependencies = MockAppDependenciesRule()

  private val sunday830am: LocalDateTime = LocalDateTime.of(2021, 7, 4, 8, 30, 0)
  private val sunday9am: LocalDateTime = LocalDateTime.of(2021, 7, 4, 9, 0, 0)
  private val sunday930am: LocalDateTime = LocalDateTime.of(2021, 7, 4, 9, 30, 0)
  private val monday830am: LocalDateTime = sunday830am.plusDays(1)
  private val utc: ZoneId = ZoneId.of("UTC")

  private val first = NotificationProfile(
    id = 1L,
    "first",
    "",
    createdAt = 1000L,
    schedule = NotificationProfileSchedule(1),
    notificationProfileId = NotificationProfileId.generate()
  )

  private val second = NotificationProfile(
    id = 2L,
    "second",
    "",
    createdAt = 2000L,
    schedule = NotificationProfileSchedule(2),
    notificationProfileId = NotificationProfileId.generate()
  )

  private lateinit var notificationProfileValues: NotificationProfileValues

  @Before
  fun setUp() {
    notificationProfileValues = mockk(relaxed = true)
    every { notificationProfileValues.manuallyEnabledUntil } returns 0
    every { notificationProfileValues.manuallyDisabledAt } returns 0

    mockkObject(SignalStore)
    every { SignalStore.notificationProfile } returns notificationProfileValues
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `when no profiles then return null`() {
    assertThat(NotificationProfiles.getActiveProfile(emptyList(), 1000L, utc), "no active profile").isNull()
  }

  @Test
  fun `when no manually enabled or schedule profiles then return null`() {
    val profiles = listOf(first, second)
    assertThat(NotificationProfiles.getActiveProfile(profiles, 3000L, utc), "no active profile").isNull()
  }

  @Test
  fun `when first is not enabled and second is manually enabled forever then return second`() {
    every { notificationProfileValues.manuallyEnabledProfile } returns second.id
    every { notificationProfileValues.manuallyEnabledUntil } returns Long.MAX_VALUE
    every { notificationProfileValues.manuallyDisabledAt } returns 5000L

    val profiles = listOf(first, second)
    assertThat(NotificationProfiles.getActiveProfile(profiles, 3000L, utc), "active profile is profile second").isEqualTo(profiles[1])
  }

  @Test
  fun `when first is scheduled and second is not manually enabled and now is within schedule return first`() {
    val schedule = NotificationProfileSchedule(id = 3L, true, start = 700, daysEnabled = setOf(DayOfWeek.SUNDAY))
    val profiles = listOf(first.copy(schedule = schedule), second)
    assertThat(NotificationProfiles.getActiveProfile(profiles, sunday9am.toMillis(ZoneOffset.UTC), utc), "active profile is first").isEqualTo(profiles[0])
  }

  @Test
  fun `when first is scheduled and second is manually enabled forever within first's schedule then return second`() {
    every { notificationProfileValues.manuallyEnabledProfile } returns second.id
    every { notificationProfileValues.manuallyEnabledUntil } returns Long.MAX_VALUE
    every { notificationProfileValues.manuallyDisabledAt } returns sunday830am.toMillis(ZoneOffset.UTC)

    val schedule = NotificationProfileSchedule(id = 3L, true, start = 700, daysEnabled = setOf(DayOfWeek.SUNDAY))
    val profiles = listOf(first.copy(schedule = schedule), second)
    assertThat(NotificationProfiles.getActiveProfile(profiles, sunday9am.toMillis(ZoneOffset.UTC), utc), "active profile is first").isEqualTo(profiles[1])
  }

  @Test
  fun `when first is scheduled and second is manually enabled forever before first's schedule start then return second`() {
    every { notificationProfileValues.manuallyEnabledProfile } returns second.id
    every { notificationProfileValues.manuallyEnabledUntil } returns Long.MAX_VALUE
    every { notificationProfileValues.manuallyDisabledAt } returns sunday830am.toMillis(ZoneOffset.UTC)

    val schedule = NotificationProfileSchedule(id = 3L, true, start = 900, daysEnabled = setOf(DayOfWeek.SUNDAY))
    val profiles = listOf(first.copy(schedule = schedule), second)

    assertThat(NotificationProfiles.getActiveProfile(profiles, sunday930am.toMillis(ZoneOffset.UTC), utc), "active profile is second").isEqualTo(profiles[1])
  }

  @Test
  fun `when first and second have overlapping schedules and second is created after first and now is within both then return second`() {
    val firstSchedule = NotificationProfileSchedule(id = 3L, true, start = 700, daysEnabled = setOf(DayOfWeek.SUNDAY))
    val secondSchedule = NotificationProfileSchedule(id = 4L, true, start = 800, daysEnabled = setOf(DayOfWeek.SUNDAY))
    val profiles = listOf(first.copy(schedule = firstSchedule), second.copy(schedule = secondSchedule))

    assertThat(NotificationProfiles.getActiveProfile(profiles, sunday9am.toMillis(ZoneOffset.UTC), utc), "active profile is second").isEqualTo(profiles[1])
  }

  @Test
  fun `when first and second have overlapping schedules and first is created before second and first is manually enabled within overlapping schedule then return first`() {
    every { notificationProfileValues.manuallyEnabledProfile } returns first.id
    every { notificationProfileValues.manuallyEnabledUntil } returns Long.MAX_VALUE
    every { notificationProfileValues.manuallyDisabledAt } returns sunday830am.toMillis(ZoneOffset.UTC)

    val firstSchedule = NotificationProfileSchedule(id = 3L, true, start = 700, daysEnabled = setOf(DayOfWeek.SUNDAY))
    val secondSchedule = NotificationProfileSchedule(id = 4L, true, start = 700, daysEnabled = setOf(DayOfWeek.SUNDAY))
    val profiles = listOf(first.copy(schedule = firstSchedule), second.copy(schedule = secondSchedule))

    assertThat(NotificationProfiles.getActiveProfile(profiles, sunday9am.toMillis(ZoneOffset.UTC), utc), "active profile is first").isEqualTo(profiles[0])
  }

  @Test
  fun `when profile is manually enabled for set time after schedule end and now is after schedule end but before manual then return profile`() {
    every { notificationProfileValues.manuallyEnabledProfile } returns first.id
    every { notificationProfileValues.manuallyEnabledUntil } returns sunday930am.toMillis(ZoneOffset.UTC)
    every { notificationProfileValues.manuallyDisabledAt } returns sunday830am.toMillis(ZoneOffset.UTC)

    val schedule = NotificationProfileSchedule(id = 3L, true, start = 700, end = 845, daysEnabled = setOf(DayOfWeek.SUNDAY))
    val profiles = listOf(first.copy(schedule = schedule))
    assertThat(NotificationProfiles.getActiveProfile(profiles, sunday9am.toMillis(ZoneOffset.UTC), utc), "active profile is first").isEqualTo(profiles[0])
  }

  @Test
  fun `when profile is manually enabled for set time before schedule end and now is after manual but before schedule end then return null`() {
    every { notificationProfileValues.manuallyEnabledProfile } returns first.id
    every { notificationProfileValues.manuallyEnabledUntil } returns sunday9am.toMillis(ZoneOffset.UTC)
    every { notificationProfileValues.manuallyDisabledAt } returns sunday830am.toMillis(ZoneOffset.UTC)

    val schedule = NotificationProfileSchedule(id = 3L, true, start = 700, end = 1000, daysEnabled = setOf(DayOfWeek.SUNDAY))
    val profiles = listOf(first.copy(schedule = schedule))
    assertThat(NotificationProfiles.getActiveProfile(profiles, sunday930am.toMillis(ZoneOffset.UTC), utc), "active profile is null").isNull()
  }

  @Test
  fun `when profile is manually enabled yesterday and is scheduled also for today then return profile`() {
    every { notificationProfileValues.manuallyEnabledProfile } returns first.id
    every { notificationProfileValues.manuallyEnabledUntil } returns sunday9am.toMillis(ZoneOffset.UTC)
    every { notificationProfileValues.manuallyDisabledAt } returns sunday830am.toMillis(ZoneOffset.UTC)

    val schedule = NotificationProfileSchedule(id = 3L, enabled = true, start = 700, end = 900, daysEnabled = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY))
    val profiles = listOf(first.copy(schedule = schedule))
    assertThat(NotificationProfiles.getActiveProfile(profiles, monday830am.toMillis(ZoneOffset.UTC), utc), "active profile is first").isEqualTo(profiles[0])
  }

  @Test
  fun `when profile is manually disabled and schedule is on but with start after end and now is before end then return null`() {
    every { notificationProfileValues.manuallyEnabledProfile } returns 0
    every { notificationProfileValues.manuallyEnabledUntil } returns 0
    every { notificationProfileValues.manuallyDisabledAt } returns sunday830am.toMillis(ZoneOffset.UTC)

    val schedule = NotificationProfileSchedule(id = 3L, enabled = true, start = 2200, end = 1000, daysEnabled = DayOfWeek.entries.toSet())
    val profiles = listOf(first.copy(schedule = schedule))
    assertThat(NotificationProfiles.getActiveProfile(profiles, sunday9am.toMillis(ZoneOffset.UTC), utc), "active profile is null").isNull()
  }
}
