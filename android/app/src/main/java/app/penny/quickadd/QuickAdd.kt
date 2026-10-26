package app.penny.quickadd

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import app.penny.MainActivity
import app.penny.R

private fun addPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0, MainActivity.addIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** A home screen button that opens the add form (behind the app lock, when it's on). */
class AddWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_add)
        views.setOnClickPendingIntent(R.id.widget_root, addPendingIntent(context))
        manager.updateAppWidget(ids, views)
    }
}

/** A Quick Settings tile that opens the add form; on a locked phone, after it's unlocked. */
class AddTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::open) else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(addPendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(MainActivity.addIntent(this))
        }
    }
}
