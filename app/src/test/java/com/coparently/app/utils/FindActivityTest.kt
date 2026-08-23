package com.coparently.app.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Compose hands a composable whatever `Context` the tree was created with, which on a themed
 * subtree is a `ContextWrapper` several layers deep rather than the `Activity` itself. A direct
 * cast returns null there; this walks down to the real one.
 */
class FindActivityTest {

    @Test
    fun `an Activity is its own answer`() {
        val activity = mockk<Activity>()
        assertSame(activity, activity.findActivity())
    }

    @Test
    fun `a wrapped Activity is found through however many layers`() {
        val activity = mockk<Activity>()
        val inner = mockk<ContextWrapper> { every { baseContext } returns activity }
        val outer = mockk<ContextWrapper> { every { baseContext } returns inner }

        assertSame(activity, outer.findActivity())
    }

    @Test
    fun `a context chain with no Activity in it returns null instead of throwing`() {
        val application = mockk<Context>()
        val wrapper = mockk<ContextWrapper> { every { baseContext } returns application }

        assertNull(wrapper.findActivity())
    }
}
