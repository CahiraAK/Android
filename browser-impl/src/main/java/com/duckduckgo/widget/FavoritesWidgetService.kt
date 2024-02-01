/*
 * Copyright (c) 2021 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.widget

import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.BrowserActivity
import com.duckduckgo.app.browser.favicon.FaviconManager
import com.duckduckgo.app.global.view.generateDefaultDrawable
import com.duckduckgo.browser.impl.R
import com.duckduckgo.common.utils.domain
import com.duckduckgo.di.scopes.RemoteViewServiceScope
import com.duckduckgo.mobile.android.R as CommonR
import com.duckduckgo.savedsites.api.SavedSitesRepository
import dagger.android.AndroidInjection
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

@InjectWith(RemoteViewServiceScope::class)
class FavoritesWidgetService : RemoteViewsService() {

    @Inject
    lateinit var savedSitesRepository: SavedSitesRepository

    @Inject
    lateinit var faviconManager: FaviconManager

    @Inject
    lateinit var widgetPrefs: WidgetPreferences

    companion object {
        const val MAX_ITEMS_EXTRAS = "MAX_ITEMS_EXTRAS"
        const val THEME_EXTRAS = "THEME_EXTRAS"
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        AndroidInjection.inject(this)
        return FavoritesWidgetItemFactory(this, intent, savedSitesRepository, faviconManager, widgetPrefs)
    }

    class FavoritesWidgetItemFactory(
        val service: Service,
        intent: Intent,
        val savedSitesRepository: SavedSitesRepository,
        val faviconManager: FaviconManager,
        val widgetPrefs: WidgetPreferences,
    ) : RemoteViewsFactory {

        private val theme = WidgetTheme.getThemeFrom(intent.extras?.getString(THEME_EXTRAS))

        private val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )

        private val faviconItemSize = service.resources.getDimension(CommonR.dimen.savedSiteGridItemFavicon).toInt()
        private val faviconItemCornerRadius = com.duckduckgo.mobile.android.R.dimen.searchWidgetFavoritesCornerRadius

        private val maxItems: Int
            get() {
                return widgetPrefs.widgetSize(appWidgetId).let { it.first * it.second }
            }

        data class WidgetFavorite(
            val title: String,
            val url: String,
            val bitmap: Bitmap?,
        )

        private val domains = mutableListOf<WidgetFavorite>()

        override fun onCreate() {
        }

        override fun onDataSetChanged() {
            val newList = savedSitesRepository.getFavoritesSync().take(maxItems).map {
                val bitmap = runBlocking {
                    faviconManager.loadFromDiskWithParams(
                        url = it.url,
                        cornerRadius = service.resources.getDimension(faviconItemCornerRadius).toInt(),
                        width = faviconItemSize,
                        height = faviconItemSize,
                    )
                        ?: generateDefaultDrawable(
                            context = service,
                            domain = it.url.extractDomain().orEmpty(),
                            cornerRadius = faviconItemCornerRadius,
                        ).toBitmap(faviconItemSize, faviconItemSize)
                }
                WidgetFavorite(it.title, it.url, bitmap)
            }
            domains.clear()
            domains.addAll(newList)
        }

        override fun onDestroy() {
        }

        override fun getCount(): Int {
            return maxItems
        }

        private fun String.extractDomain(): String? {
            return if (this.startsWith("http")) {
                this.toUri().domain()
            } else {
                "https://$this".extractDomain()
            }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val item = if (position >= domains.size) null else domains[position]
            val remoteViews = RemoteViews(service.packageName, getItemLayout())
            if (item != null) {
                if (item.bitmap != null) {
                    remoteViews.setImageViewBitmap(R.id.quickAccessFavicon, item.bitmap)
                }
                remoteViews.setViewVisibility(R.id.quickAccessTitle, View.VISIBLE)
                remoteViews.setTextViewText(R.id.quickAccessTitle, item.title)
                configureClickListener(remoteViews, item.url)
            } else {
                remoteViews.setViewVisibility(R.id.quickAccessTitle, View.GONE)
                remoteViews.setImageViewResource(R.id.quickAccessFavicon, getEmptyBackgroundDrawable())
            }

            return remoteViews
        }

        private fun getItemLayout(): Int {
            return when (theme) {
                WidgetTheme.LIGHT -> R.layout.view_favorite_widget_light_item
                WidgetTheme.DARK -> R.layout.view_favorite_widget_dark_item
                WidgetTheme.SYSTEM_DEFAULT -> R.layout.view_favorite_widget_daynight_item
            }
        }

        private fun getEmptyBackgroundDrawable(): Int {
            return when (theme) {
                WidgetTheme.LIGHT -> R.drawable.search_widget_favorite_favicon_light_background
                WidgetTheme.DARK -> R.drawable.search_widget_favorite_favicon_dark_background
                WidgetTheme.SYSTEM_DEFAULT -> R.drawable.search_widget_favorite_favicon_daynight_background
            }
        }

        private fun configureClickListener(
            remoteViews: RemoteViews,
            item: String,
        ) {
            val bundle = Bundle()
            bundle.putString(Intent.EXTRA_TEXT, item)
            bundle.putBoolean(BrowserActivity.NEW_SEARCH_EXTRA, false)
            bundle.putBoolean(BrowserActivity.LAUNCH_FROM_FAVORITES_WIDGET, true)
            bundle.putBoolean(BrowserActivity.NOTIFY_DATA_CLEARED_EXTRA, false)
            val intent = Intent()
            intent.putExtras(bundle)
            remoteViews.setOnClickFillInIntent(R.id.quickAccessFaviconContainer, intent)
        }

        override fun getLoadingView(): RemoteViews {
            return RemoteViews(service.packageName, getItemLayout())
        }

        override fun getViewTypeCount(): Int {
            return 1
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun hasStableIds(): Boolean {
            return true
        }
    }
}
