package io.github.jichuo1.meguviz.android.extensions

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.text.SpannableString
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 29, 30, 37], manifest = Config.NONE)
class ExtensionsGoldenTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun textColorAndConversionPreserveStateAndNullContract() {
        val view = TextView(context)
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.RED, Color.BLUE)))
        assertEquals(Color.RED, view.textColor)
        view.isEnabled = false
        assertEquals(Color.BLUE, view.textColor)
        view.textColor = Color.GREEN
        assertEquals(Color.GREEN, view.currentTextColor)
        assertFalse(view.textColors.isStateful)

        view.text = SpannableString("hello")
        assertEquals("hello", view.textToString())
        view.text = null
        assertEquals("", view.textToString())
        view.text = "再见"
        assertEquals("再见", view.textToString())
    }

    @Test fun childAndParentHandleBoundsTypesAndRemoval() {
        val root = FrameLayout(context)
        assertNull(root.firstChildOrNull)
        assertNull(root.childOrNull(-1))
        assertThrows(IllegalStateException::class.java) { root.child(0) }
        val child = TextView(context)
        root.addView(child)
        assertSame(child, root.child<TextView>(0))
        assertSame(child, root.firstChildOrNull<TextView>())
        assertNull(root.childOrNull<FrameLayout>(0))
        assertNull(root.childOrNull(1))
        assertThrows(IllegalStateException::class.java) { root.child<FrameLayout>(0) }
        assertThrows(IllegalStateException::class.java) { root.child(-1) }

        val ownedRoot = FrameLayout(context)
        val ownedChild = View(context)
        ownedChild.removeSelf()
        assertNull(ownedChild.parentOrNull())
        ownedRoot.addView(ownedChild)
        assertSame(ownedRoot, ownedChild.parentOrNull<FrameLayout>())
        assertNull(ownedChild.parentOrNull<LinearLayout>())
        ownedChild.removeSelf()
        assertNull(ownedChild.parent)
        assertEquals(0, ownedRoot.childCount)
        ownedChild.removeSelf()
    }

    @Test fun paddingAndMarginsRetainOmittedEdgesAndUseAbsoluteCoordinates() {
        val view = View(context)
        view.layoutDirection = View.LAYOUT_DIRECTION_RTL
        view.setPadding(1, 2, 3, 4)
        view.updatePadding(horizontal = 7)
        assertEquals(listOf(7, 2, 7, 4), padding(view))
        view.updatePadding(horizontal = -10, vertical = 0)
        assertEquals(listOf(7, 0, 7, 0), padding(view))
        view.updatePadding()
        assertEquals(listOf(7, 0, 7, 0), padding(view))

        val marginView = View(context)
        val wrong = ViewGroup.LayoutParams(20, 30)
        marginView.layoutParams = wrong
        marginView.setMargins(99)
        marginView.updateMargins(horizontal = 9)
        assertSame(wrong, marginView.layoutParams)
        val params = FrameLayout.LayoutParams(20, 30)
        params.setMargins(1, 2, 3, 4)
        marginView.layoutParams = params
        marginView.updateMargins(vertical = 8)
        assertEquals(listOf(1, 8, 3, 8), margins(params))
        assertTrue(marginView.isLayoutRequested)
        params.updateMargins(horizontal = 6)
        assertEquals(listOf(6, 8, 6, 8), margins(params))
        marginView.setMargins(-1, 0, 5, 9)
        assertEquals(listOf(-1, 0, 5, 9), margins(params))
        marginView.setMargins(4)
        assertEquals(listOf(4, 4, 4, 4), margins(params))
    }

    @Test fun dpAndSpPreserveNumberTypesSentinelsAndTruncation() {
        assertEquals(5, dpToPixels(2, 2.75f))
        assertEquals(5L, dpToPixels(2L, 2.75f))
        assertEquals(5.5f, dpToPixels(2f, 2.75f))
        assertEquals(5.5, dpToPixels(2.0, 2.75f), 0.0)
        assertEquals(-2, dpToPixels(-2, 3f))
        assertEquals(-1.5f, dpToPixels(-1.5f, 3f))
        assertEquals(0, dpToPixels(0, 3f))
        assertThrows(IllegalStateException::class.java) { dpToPixels(1.toShort(), 3f) }
        assertEquals(dpToPixels(20, context.resources.displayMetrics.density), 20.toPx(context))

        val resources = context.resources
        assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
            16f, resources.displayMetrics), 16f.spToPx(resources), 0f)
    }

    @Test fun nightModeUsesProvidedConfiguration() {
        val configuration = Configuration()
        configuration.uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_CAR
        assertTrue(configuration.isUiInNightMode)
        configuration.uiMode = Configuration.UI_MODE_NIGHT_NO
        assertFalse(configuration.isUiInNightMode)
        configuration.uiMode = Configuration.UI_MODE_NIGHT_UNDEFINED
        assertFalse(configuration.isUiInNightMode)
    }

    @Test fun versionCodeAndSdkPredicatesRetainHighBitsAndBounds() {
        val info = PackageInfo()
        @Suppress("DEPRECATION")
        run { info.versionCode = 123 }
        if (Build.VERSION.SDK_INT >= 28) {
            info.setLongVersionCode(0x1_0000_007bL)
            assertEquals(0x1_0000_007bL, info.versionCodeCompat)
        } else assertEquals(123L, info.versionCodeCompat)

        assertEquals(Build.VERSION.SDK_INT, AndroidVersion.code)
        assertTrue(AndroidVersion.isAtLeast(Build.VERSION.SDK_INT))
        assertTrue(AndroidVersion.isAtMost(Build.VERSION.SDK_INT))
        assertFalse(AndroidVersion.isLessThan(Build.VERSION.SDK_INT))
        assertFalse(AndroidVersion.isAtLeast(Build.VERSION.SDK_INT + 1))
        assertFalse(AndroidVersion.isAtMost(Build.VERSION.SDK_INT - 1))
    }

    @Test fun toastDefaultsDurationAndBackgroundRejection() {
        context.toast("short")
        assertEquals("short", ShadowToast.getTextOfLatestToast())
        assertEquals(Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
        View(context).toast("long", Toast.LENGTH_LONG)
        assertEquals("long", ShadowToast.getTextOfLatestToast())
        assertEquals(Toast.LENGTH_LONG, ShadowToast.getLatestToast().duration)

        val before = ShadowToast.shownToastCount()
        val caught = AtomicReference<Throwable>()
        Thread { try { context.toast("forbidden") } catch (error: Throwable) { caught.set(error) } }
            .also { it.start(); it.join() }
        assertTrue(caught.get() is IllegalStateException)
        assertEquals(before, ShadowToast.shownToastCount())
    }

    @Test fun componentFlagsManifestDefaultsAndStateSemantics() {
        val manager = context.packageManager
        val component = ComponentName(context.packageName, "TestAlias")
        val applicationInfo = ApplicationInfo().apply { packageName = context.packageName; enabled = true }
        val info = ActivityInfo().apply {
            name = component.className
            packageName = component.packageName
            this.applicationInfo = applicationInfo
            enabled = false
        }
        shadowOf(manager).addOrUpdateActivity(info)
        assertFalse(manager.isActivityComponentEnabled(component))
        manager.enableComponent(component)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, manager.getComponentEnabledSetting(component))
        assertTrue(manager.isActivityComponentEnabled(component))
        manager.disableComponent(component)
        assertFalse(manager.isActivityComponentEnabled(component))
        manager.enableComponent(component)
        manager.setApplicationEnabledSetting(component.packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        assertFalse(manager.isActivityComponentEnabled(component))

        assertFalse(effectiveEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, false))
        assertTrue(effectiveEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, true))
        assertTrue(effectiveEnabled(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, false))
        for (state in intArrayOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED, 99)) {
            assertFalse(effectiveEnabled(state, true))
        }
    }

    @Test fun rootInsetsProbeAndRemovalDetachDispatchExactlyOnce() {
        val view = View(context)
        assertNull(view.rootStatusBarInsetTopOrNull())
        assertFalse(view.isAttachedToWindow)

        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(controller.get())
            val child = View(controller.get())
            root.addView(child)
            controller.get().setContentView(root)
            var detach = 0
            child.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) { detach++ }
            })
            assertTrue(child.isAttachedToWindow)
            child.removeSelf()
            child.removeSelf()
            assertEquals(1, detach)
        } finally { controller.pause().stop().destroy() }
    }

    private fun padding(view: View) = listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    private fun margins(params: ViewGroup.MarginLayoutParams) =
        listOf(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin)
}
