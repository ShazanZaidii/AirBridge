package com.shazan.airbridge.control.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * The way to Android's Accessibility settings with our service's row picked out and scrolled to, so nobody has to hunt for
 * it in a long list. It only points: nothing is switched on from here, the person still opens the row and turns it on.
 *
 * Android has no public call for "open this service's page" (the details page needs a system-only permission), but its
 * Settings app scrolls to and highlights the row named by the `:settings:fragment_args_key` extra, which is the service's
 * component name. Phones whose Settings app ignores it simply open the usual list, so the setup screen also says where to
 * look.
 */
object AccessibilityLink {
    private const val KEY = ":settings:fragment_args_key"
    private const val ARGS = ":settings:show_fragment_args"

    fun intent(ctx: Context, service: Class<*> = ControlAccessibilityService::class.java): Intent {
        val row = ComponentName(ctx, service).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(KEY, row)
            .putExtra(ARGS, Bundle().apply { putString(KEY, row) })
    }
}
