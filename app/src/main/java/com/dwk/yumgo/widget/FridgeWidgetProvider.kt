package com.dwk.yumgo.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen summary of the fridge.
 *
 * Declared unexported: the system is the only sender the launcher picker needs, and no other app can
 * drive it. The provider is unicast, so [FridgeWidgetUpdates] refreshes placed instances directly
 * after a save instead of broadcasting.
 *
 * Rendering reads the local fridge on a background thread and finishes the broadcast with
 * [goAsync], so the read survives a slow start and the system does not kill the process mid-update.
 */
class FridgeWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    renderAsync(context.applicationContext, manager, ids)
  }

  override fun onAppWidgetOptionsChanged(
    context: Context,
    manager: AppWidgetManager,
    id: Int,
    newOptions: Bundle,
  ) {
    super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
    renderAsync(context.applicationContext, manager, intArrayOf(id))
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ACTION_REFRESH) {
      val manager = AppWidgetManager.getInstance(context)
      val single = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
      val ids = if (single == AppWidgetManager.INVALID_APPWIDGET_ID) placedIds(context) else intArrayOf(single)
      renderAsync(context.applicationContext, manager, ids)
      return
    }
    super.onReceive(context, intent)
  }

  private fun renderAsync(context: Context, manager: AppWidgetManager, ids: IntArray) {
    if (ids.isEmpty()) return
    val pendingResult = goAsync()
    RenderScope.launch {
      try {
        FridgeWidgetRenderer.render(context, manager, ids)
      } catch (error: Throwable) {
        Log.w(LOG_TAG, "Fridge widget update failed", error)
      } finally {
        runCatching { pendingResult.finish() }
      }
    }
  }

  companion object {
    /** Our own action. The receiver is unexported, so only this app can send it. */
    const val ACTION_REFRESH = "com.dwk.yumgo.widget.action.REFRESH"

    private const val LOG_TAG = "FridgeWidget"

    private val RenderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every placed instance of this provider, or an empty array when none are on a home screen. */
    fun placedIds(context: Context): IntArray =
      AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, FridgeWidgetProvider::class.java))
  }
}
