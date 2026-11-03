package com.family.grandmaphone.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.family.grandmaphone.R
import com.family.grandmaphone.call.CallHistory
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.home.MainActivity
import java.io.File

/**
 * A home screen page of her people's faces, in the same order as the app. Placed on the page
 * next to the main one, a swipe brings her faces up, and a tap opens that person.
 */
class FacesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context, id))
        manager.notifyAppWidgetViewDataChanged(ids, R.id.grid)
    }

    private fun views(context: Context, widgetId: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.faces_widget)
        val adapter = Intent(context, FacesWidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        // Makes each widget's adapter intent distinct, as Android requires.
        adapter.data = android.net.Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
        views.setRemoteAdapter(R.id.grid, adapter)
        views.setEmptyView(R.id.grid, R.id.empty)
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Each face fills in its own person. Mutable so Android can add that.
        views.setPendingIntentTemplate(
            R.id.grid,
            PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE),
        )
        val app = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.title, app)
        views.setOnClickPendingIntent(R.id.empty, app)
        return views
    }

    companion object {
        /** Redraws the faces after people, photos, settings or calls change. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, FacesWidget::class.java))
            if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, R.id.grid)
        }
    }
}

class FacesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Faces(applicationContext)

    private class Faces(private val context: Context) : RemoteViewsFactory {
        private var people: List<Contact> = emptyList()
        private var missed: Set<String> = emptySet()

        override fun onCreate() = Unit
        override fun onDestroy() = Unit

        override fun onDataSetChanged() {
            val visible = Repo.visibleContacts(Repo.contacts.value, Repo.settings.value)
            people = CallHistory.recentFirst(visible, CallHistory.entries.value, CallHistory.whatsAppCalls.value)
            missed = Repo.missedCalls.value
        }

        override fun getCount() = people.size
        override fun getViewTypeCount() = 1
        override fun hasStableIds() = true
        override fun getItemId(position: Int) = people.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.faces_widget_item)
            val person = people.getOrNull(position) ?: return views
            views.setTextViewText(R.id.name, person.name)
            val photo = roundedPhoto(Repo.photoFile(person.id)) ?: roundedPhoto(Repo.whatsAppPhotoFile(person.id))
            if (photo != null) {
                views.setImageViewBitmap(R.id.photo, photo)
            } else {
                views.setImageViewResource(R.id.photo, R.drawable.widget_silhouette)
            }
            views.setViewVisibility(R.id.missed, if (person.id in missed) View.VISIBLE else View.GONE)
            views.setOnClickFillInIntent(R.id.item, Intent().putExtra(MainActivity.EXTRA_CONTACT, person.id))
            return views
        }

        /** Widgets can't round corners themselves, so the photo is cut to shape here. */
        private fun roundedPhoto(file: File): Bitmap? {
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
            val source = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val side = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, SIDE, SIDE, true)
            val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawRoundRect(RectF(0f, 0f, SIDE.toFloat(), SIDE.toFloat()), RADIUS, RADIUS, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            return out
        }
    }

    private companion object {
        const val SIDE = 320
        const val RADIUS = 48f
    }
}
