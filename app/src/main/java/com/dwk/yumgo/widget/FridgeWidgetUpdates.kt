package com.dwk.yumgo.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tells placed widgets that the fridge changed.
 *
 * Called by the repository after a commit, never instead of one: the save is already durable when
 * this runs, so a missing widget, a dead process, or a failing binder call must stay invisible to
 * the user. Everything here is fire-and-forget on its own scope and swallows failures.
 */
internal object FridgeWidgetUpdates {
  private const val LOG_TAG = "FridgeWidget"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  fun itemsChanged(context: Context) {
    val appContext = context.applicationContext
    scope.launch {
      val ids = runCatching { FridgeWidgetProvider.placedIds(appContext) }.getOrNull() ?: return@launch
      if (ids.isEmpty()) return@launch
      runCatching { FridgeWidgetRenderer.render(appContext, AppWidgetManager.getInstance(appContext), ids) }
        .onFailure { Log.w(LOG_TAG, "Could not refresh the fridge widget", it) }
    }
  }
}
