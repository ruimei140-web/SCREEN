package com.wordlookup.screen.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.wordlookup.screen.R
import com.wordlookup.screen.data.VocabularyDatabase
import com.wordlookup.screen.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VocabWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun refreshNow(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VocabWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateAllWidgets(context, manager, ids)
            }
        }

        private fun updateAllWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                val topWords = VocabularyDatabase.getInstance(context)
                    .vocabularyDao()
                    .getTopBlindspots()

                val bodyText = if (topWords.isEmpty()) {
                    "暂无生词，在屏幕长按取词后将自动同步到这里。"
                } else {
                    topWords.joinToString("\\n") { item ->
                        "• ${item.word} — ${item.meaning.substringBefore("；")} (查${item.lookupCount}次)"
                    }
                }

                val intent = Intent(context, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                for (id in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_vocab_review).apply {
                        setTextViewText(R.id.widgetBody, bodyText)
                        setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
                    }
                    appWidgetManager.updateAppWidget(id, views)
                }
            }
        }
    }
}
r