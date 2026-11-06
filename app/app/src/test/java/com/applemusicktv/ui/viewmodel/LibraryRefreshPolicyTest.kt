package com.applemusicktv.ui.viewmodel

import org.junit.Assert.*
import org.junit.Test

class LibraryRefreshPolicyTest {
    @Test fun loadingOneCategoryNeverMarksOtherCategoriesFresh() {
        val policy=LibraryRefreshPolicy {0};policy.succeeded(LibrarySection.Songs)
        assertTrue(policy.isFresh(LibrarySection.Songs))
        assertFalse(policy.isFresh(LibrarySection.Albums));assertFalse(policy.isFresh(LibrarySection.Artists))
    }
    @Test fun emptySuccessIsFreshUntilFiveMinutesAndClockRegressionIsStale() {
        var now=100L;val policy=LibraryRefreshPolicy {now};policy.succeeded(LibrarySection.Playlists)
        now+=299999;assertTrue(policy.isFresh(LibrarySection.Playlists))
        now++;assertFalse(policy.isFresh(LibrarySection.Playlists))
        now=1;assertFalse(policy.isFresh(LibrarySection.Playlists))
    }
    @Test fun explicitRefreshAndAccountChangesInvalidateAllCategories() {
        val policy=LibraryRefreshPolicy {0};LibrarySection.entries.forEach(policy::succeeded)
        policy.invalidate();assertTrue(LibrarySection.entries.none(policy::isFresh))
    }
}
