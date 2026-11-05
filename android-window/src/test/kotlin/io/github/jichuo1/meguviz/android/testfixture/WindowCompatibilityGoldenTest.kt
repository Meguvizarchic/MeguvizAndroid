package io.github.jichuo1.meguviz.android.testfixture

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the bundled window compatibility fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 29, 30, 37], manifest = Config.NONE)
@Suppress("DEPRECATION")
class WindowCompatibilityGoldenTest {
    @Test fun initializationDefaultsAndPaddingBaselinePlusSafeInsets() {
        withWindow { activity, root ->
            val delegate = WindowCompatibilityFixture(activity, root)
            try {
                val window = activity.window
                val controller = WindowCompat.getInsetsController(window, root)
                assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    controller.systemBarsBehavior)
                assertEquals(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                    window.attributes.flags and WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                if (Build.VERSION.SDK_INT < 35) {
                    assertEquals(Color.TRANSPARENT, window.statusBarColor)
                    assertEquals(Color.TRANSPARENT, window.navigationBarColor)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    assertFalse(window.isStatusBarContrastEnforced)
                    assertFalse(window.isNavigationBarContrastEnforced)
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                        window.attributes.layoutInDisplayCutoutMode)
                }
            } finally { delegate.close() }
        }
        withWindow { activity, root ->
            root.setPadding(1, 2, 3, 4)
            val delegate = WindowCompatibilityFixture(activity, root)
            try {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(root.isAttachedToWindow)
                val platformSeed = requireNotNull(root.rootWindowInsets) { "Attached window must provide root insets" }
                val seed = WindowInsetsCompat.toWindowInsetsCompat(platformSeed, root)
                assertFalse("Root insets seed must be unconsumed", seed.isConsumed)
                val imeHeight = seed.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()).bottom + 200
                val builder = WindowInsetsCompat.Builder(seed)
                if (Build.VERSION.SDK_INT < 30) {
                    // Older platform WindowInsets stores IME and navigation in one legacy bottom field.
                    // AndroidX separates them using the attached root's actual stable navigation inset.
                    builder.setSystemWindowInsets(Insets.of(5, 20, 7, imeHeight))
                } else {
                    builder.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(5, 20, 7, 30))
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeight))
                }
                val insets = builder.build()
                assertFalse("Fixture must provide unconsumed input", insets.isConsumed)
                val forwarded = ViewCompat.dispatchApplyWindowInsets(root, insets)
                assertFalse(forwarded.isConsumed)
                // ViewCompat may return the unchanged builder object without attaching root metadata.
                // Rewrap the platform result exactly as AndroidX does for the actual listener input.
                val observed = WindowInsetsCompat.toWindowInsetsCompat(requireNotNull(forwarded.toWindowInsets()), root)
                val safe = observed.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime = observed.getInsets(WindowInsetsCompat.Type.ime())
                assertTrue("Fixture must distinguish IME from safe drawing", ime.bottom > safe.bottom)
                val expected = listOf(1 + safe.left, 2 + safe.top, 3 + safe.right, 4 + safe.bottom)
                assertEquals(expected, padding(root))
                assertTrue(root.paddingBottom < 4 + ime.bottom)
                ViewCompat.dispatchApplyWindowInsets(root, insets)
                assertEquals(expected, padding(root))
            } finally { delegate.close() }
            assertEquals(listOf(1, 2, 3, 4), padding(root))
        }
    }

    @Test fun listenerHandoffAndCloseDoNotOverwriteLaterOwners() {
        withWindow { activity, root ->
            val delegate = WindowCompatibilityFixture(activity, root)
            delegate.handOffInsets(root)
            var callbacks = 0
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> callbacks++; insets }
            try {
                delegate.close()
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 20, 0, 30)).build()
                ViewCompat.dispatchApplyWindowInsets(root, insets)
                assertEquals(1, callbacks)
                delegate.close()
                ViewCompat.dispatchApplyWindowInsets(root, insets)
                assertEquals(2, callbacks)
            } finally { ViewCompat.setOnApplyWindowInsetsListener(root, null); delegate.close() }
        }
        withWindow { activity, root ->
            val delegate = WindowCompatibilityFixture(activity, root)
            root.setPadding(17, 18, 19, 20)
            if (Build.VERSION.SDK_INT < 35) activity.window.navigationBarColor = Color.MAGENTA
            delegate.close()
            assertEquals(listOf(17, 18, 19, 20), padding(root))
            if (Build.VERSION.SDK_INT < 35) assertEquals(Color.MAGENTA, activity.window.navigationBarColor)
        }
    }

    @Test fun ownerReopenAfterCloseAndDuplicateOwnerRejection() {
        withWindow { activity, root ->
            val first = WindowCompatibilityFixture(activity, root)
            first.setStarted(true)
            first.setStarted(false)
            first.close()
            first.setStarted(true)
            first.refreshConfiguration()
            first.invalidateTheme()
            first.close()
            WindowCompatibilityFixture(activity, root).close()
        }
        withWindow { activity, root ->
            val delegate = WindowCompatibilityFixture(activity, root)
            try { assertThrows(IllegalStateException::class.java) { WindowCompatibilityFixture(activity, root) } }
            finally { delegate.close() }
            WindowCompatibilityFixture(activity, root).close()
        }
    }

    @Test fun invalidContentReleasesWindowOwnership() = withWindow { activity, root ->
        assertThrows(IllegalArgumentException::class.java) { WindowCompatibilityFixture(activity, View(activity)) }
        WindowCompatibilityFixture(activity, root).close()
    }

    private fun padding(view: View) = listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)

    private fun withWindow(block: (Activity, FrameLayout) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            block(activity, root)
        } finally { controller.pause().stop().destroy() }
    }
}
