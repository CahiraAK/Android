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
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.favicon.FaviconManager
import com.duckduckgo.browser.impl.R
import com.duckduckgo.di.scopes.RemoteViewServiceScope
import com.duckduckgo.savedsites.api.SavedSitesRepository
import dagger.android.AndroidInjection
import javax.inject.Inject

@InjectWith(RemoteViewServiceScope::class)
class EmptyFavoritesWidgetService : RemoteViewsService() {

    @Inject
    lateinit var savedSitesRepository: SavedSitesRepository

    @Inject
    lateinit var faviconManager: FaviconManager

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        AndroidInjection.inject(this)
        return EmptyFavoritesWidgetItemFactory(this, savedSitesRepository, faviconManager)
    }

    /**
     * This RemoteViewsFactory will not render any item. It's used by is used for convenience to simplify executing background operations to show/hide empty widget CTA.
     * If this RemoteViewsFactory count is 0, SearchAndFavoritesWidget R.id.emptyfavoritesGrid will show the configured EmptyView.
     */
    class EmptyFavoritesWidgetItemFactory(
        val service: Service,
        val savedSitesRepository: SavedSitesRepository,
        val faviconManager: FaviconManager,
    ) : RemoteViewsFactory {

        private var count = 0

        override fun onCreate() {
        }

        override fun onDataSetChanged() {
            count = if (savedSitesRepository.hasFavorites()) 1 else 0
        }

        override fun onDestroy() {
        }

        override fun getCount(): Int {
            return count
        }

        override fun getViewAt(position: Int): RemoteViews {
            return RemoteViews(service.packageName, R.layout.empty_view)
        }

        override fun getLoadingView(): RemoteViews {
            return RemoteViews(service.packageName, R.layout.empty_view)
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
