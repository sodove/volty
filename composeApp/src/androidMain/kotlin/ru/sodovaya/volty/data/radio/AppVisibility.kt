package ru.sodovaya.volty.data.radio

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * True while one of the app's activities is resumed. The radio's auto-join starts a microphone
 * foreground service, which Android 14+ refuses from the background (ruling R20), so it asks this
 * first. Register once with [Application.registerActivityLifecycleCallbacks].
 */
class AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumed = 0

    val isVisible: Boolean get() = resumed > 0

    override fun onActivityResumed(activity: Activity) {
        resumed++
    }

    override fun onActivityPaused(activity: Activity) {
        resumed = (resumed - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
