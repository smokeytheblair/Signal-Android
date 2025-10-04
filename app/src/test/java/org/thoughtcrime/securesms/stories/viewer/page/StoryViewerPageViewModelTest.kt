package org.thoughtcrime.securesms.stories.viewer.page

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.database.FakeMessageRecords
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoryViewerPageViewModelTest {
  private val repository = mockk<StoryViewerPageRepository>(relaxed = true)
  private val testScheduler = TestScheduler()

  @Before
  fun setUp() {
    RxJavaPlugins.setInitIoSchedulerHandler { testScheduler }
    RxJavaPlugins.setIoSchedulerHandler { testScheduler }

    RxJavaPlugins.setInitComputationSchedulerHandler { testScheduler }
    RxJavaPlugins.setComputationSchedulerHandler { testScheduler }

    RxAndroidPlugins.setMainThreadSchedulerHandler { testScheduler }

    every { repository.forceDownload(any()) } returns Completable.complete()
  }

  @After
  fun tearDown() {
    RxJavaPlugins.reset()
  }

  @Test
  fun `Given first page and first post, when I goToPreviousPost, then I expect storyIndex to be 0`() {
    // GIVEN
    val storyPosts = createStoryPosts(3) { true }
    every { repository.getStoryPostsFor(any(), any()) } returns Observable.just(storyPosts)
    val testSubject = createTestSubject()
    testSubject.setIsFirstPage(true)
    testScheduler.triggerActions()

    // WHEN
    testSubject.goToPreviousPost()
    testScheduler.triggerActions()

    // THEN
    val testSubscriber = testSubject.state.test()

    testSubscriber.assertValueAt(0) { it.selectedPostIndex == 0 }
  }

  @Test
  fun `Given first page and second post, when I goToPreviousPost, then I expect storyIndex to be 0`() {
    // GIVEN
    val storyPosts = createStoryPosts(3) { true }
    every { repository.getStoryPostsFor(any(), any()) } returns Observable.just(storyPosts)
    val testSubject = createTestSubject()
    testSubject.setIsFirstPage(true)
    testScheduler.triggerActions()
    testSubject.goToNextPost()
    testScheduler.triggerActions()

    // WHEN
    testSubject.goToPreviousPost()
    testScheduler.triggerActions()

    // THEN
    val testSubscriber = testSubject.state.test()

    testSubscriber.assertValueAt(0) { it.selectedPostIndex == 0 }
  }

  @Test
  fun `Given no initial story and 3 records all viewed, when I initialize, then I expect storyIndex to be 0`() {
    // GIVEN
    val storyPosts = createStoryPosts(3) { true }
    every { repository.getStoryPostsFor(any(), any()) } returns Observable.just(storyPosts)

    // WHEN
    val testSubject = createTestSubject()

    // THEN
    testScheduler.triggerActions()
    val testSubscriber = testSubject.state.test()

    testSubscriber.assertValueAt(0) { it.selectedPostIndex == 0 }
  }

  @Test
  fun `Given no initial story and 3 records all not viewed, when I initialize, then I expect storyIndex to be 0`() {
    // GIVEN
    val storyPosts = createStoryPosts(3) { false }
    every { repository.getStoryPostsFor(any(), any()) } returns Observable.just(storyPosts)

    // WHEN
    val testSubject = createTestSubject()

    // THEN
    testScheduler.triggerActions()
    val testSubscriber = testSubject.state.test()

    testSubscriber.assertValueAt(0) { it.selectedPostIndex == 0 }
  }

  @Test
  fun `Given no initial story and 3 records with 2nd is not viewed, when I initialize, then I expect storyIndex to be 1`() {
    // GIVEN
    val storyPosts = createStoryPosts(3) { it % 2 != 0 }
    every { repository.getStoryPostsFor(any(), any()) } returns Observable.just(storyPosts)

    // WHEN
    val testSubject = createTestSubject()

    // THEN
    testScheduler.triggerActions()
    val testSubscriber = testSubject.state.test()

    testSubscriber.assertValueAt(0) { it.selectedPostIndex == 1 }
  }

  @Test
  fun `Given no initial story and 3 records with 1st and 3rd not viewed, when I goToNext, then I expect storyIndex to be 2`() {
    // GIVEN
    val storyPosts = createStoryPosts(3) { it % 2 == 0 }
    every { repository.getStoryPostsFor(any(), any()) } returns Observable.just(storyPosts)

    // WHEN
    val testSubject = createTestSubject()
    testScheduler.triggerActions()
    testSubject.goToNextPost()
    testScheduler.triggerActions()

    // THEN
    val testSubscriber = testSubject.state.test()

    testSubscriber.assertValueAt(0) { it.selectedPostIndex == 2 }
  }

  @Test
  fun `Given no unread and jump to next unread enabled, when I goToNext, then I expect storyIndex to be size`() {
    // GIVEN
    val storyPosts = createStoryPosts(3) { true }
    every { repository.getStoryPostsFor(any(), any()) } returns Observable.just(storyPosts)

    // WHEN
    val testSubject = createTestSubject(isJumpForwardToUnviewed = true)
    testScheduler.triggerActions()
    testSubject.goToNextPost()
    testScheduler.triggerActions()

    // THEN
    val testSubscriber = testSubject.state.test()

    testSubscriber.assertValueAt(0) { it.selectedPostIndex == 3 }
  }

  @Test
  fun `Given a single story, when I goToPrevious, then I expect storyIndex to be -1`() {
    // GIVEN
    val storyPosts = createStoryPosts(1)
    every { repository.getStoryPostsFor(any(), any()) } returns Observable.just(storyPosts)

    // WHEN
    val testSubject = createTestSubject()
    testScheduler.triggerActions()
    testSubject.goToPreviousPost()
    testScheduler.triggerActions()

    // THEN
    val testSubscriber = testSubject.state.test()

    testSubscriber.assertValueAt(0) { it.selectedPostIndex == -1 }
  }

  private fun createTestSubject(isJumpForwardToUnviewed: Boolean = false): StoryViewerPageViewModel {
    return StoryViewerPageViewModel(
      StoryViewerPageArgs(
        recipientId = RecipientId.from(1),
        initialStoryId = -1L,
        isOutgoingOnly = false,
        isJumpForwardToUnviewed = isJumpForwardToUnviewed,
        source = StoryViewerPageArgs.Source.UNKNOWN,
        groupReplyStartPosition = -1
      ),
      repository,
      mockk()
    )
  }

  private fun createStoryPosts(count: Int, isViewed: (Int) -> Boolean = { false }): List<StoryPost> {
    return (1..count).map {
      StoryPost(
        id = it.toLong(),
        sender = Recipient.UNKNOWN,
        group = null,
        distributionList = null,
        viewCount = 0,
        replyCount = 0,
        dateInMilliseconds = it.toLong(),
        content = StoryPost.Content.TextContent(mockk(), it.toLong(), false, 0),
        conversationMessage = mockk(),
        allowsReplies = true,
        hasSelfViewed = isViewed(it)
      ).apply {
        val messageRecord = FakeMessageRecords.buildMediaMmsMessageRecord()
        every { conversationMessage.messageRecord } returns messageRecord
      }
    }
  }
}
