/*
 * Copyright (c) 2017 DuckDuckGo
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

package com.duckduckgo.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
import android.print.PrintDocumentAdapter
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.DownloadListener
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebViewClient
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewCompat.WebMessageListener
import androidx.webkit.WebViewFeature
import com.duckduckgo.app.browser.navigation.safeCopyBackForwardList
import com.duckduckgo.browser.api.WebViewVersionProvider
import com.duckduckgo.common.ui.view.hide
import com.duckduckgo.common.ui.view.show
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.extensions.compareSemanticVersion
import kotlinx.coroutines.withContext
import timber.log.Timber

interface DuckDuckGoWebView : NestedScrollingChild3 {
    fun getWebView(): WebView
    fun safeCopyBackForwardList(): WebBackForwardList?
    fun show()
    fun hide()
    fun removeEnableSwipeRefreshCallback()
    fun setEnableSwipeRefreshCallback(callback: (Boolean) -> Unit)
    fun destroy()
    fun setBottomMatchingBehaviourEnabled(value: Boolean)
    fun stopLoading()
    fun onPause()
    fun onResume()
    fun loadUrl(url: String)
    fun loadUrl(url: String, headers: Map<String, String>)
    fun reload()
    fun goForward()
    fun goBackOrForward(steps: Int)
    fun findAllAsync(searchTerm: String)
    fun findNext(forward: Boolean)
    fun clearSslPreferences()
    fun setDownloadListener(listener: DownloadListener?)
    fun setOnTouchListener(listener: OnTouchListener)
    fun canScrollVertically(direction: Int): Boolean?
    fun requestFocusNodeHref(message: Message?)
    fun createPrintDocumentAdapter(url: String): PrintDocumentAdapter
    fun getHitTestResult(): HitTestResult
    fun setWebChromeClient(client: WebChromeClient?)
    fun setWebViewClient(client: WebViewClient)
    fun getSettings(): WebSettings
    fun isShown(): Boolean
    fun getUrl(): String?
    fun setFindListener(listener: WebView.FindListener?)
    suspend fun safeAddWebMessageListener(
        dispatchers: DispatcherProvider,
        webViewVersionProvider: WebViewVersionProvider,
        jsObjectName: String,
        allowedOriginRules: Set<String>,
        listener: WebMessageListener,
    ): Boolean
    suspend fun safeRemoveWebMessageListener(
        dispatchers: DispatcherProvider,
        webViewVersionProvider: WebViewVersionProvider,
        jsObjectName: String,
    ): Boolean
    suspend fun isWebMessageListenerSupported(
        dispatchers: DispatcherProvider,
        webViewVersionProvider: WebViewVersionProvider,
    ): Boolean
    val isDestroyed: Boolean
}

/**
 * WebView subclass which allows the WebView to
 *   - hide the toolbar when placed in a CoordinatorLayout
 *   - add the flag so that users' typing isn't used for personalisation
 *
 * Originally based on https://github.com/takahirom/webview-in-coordinatorlayout for scrolling behaviour
 */
class RealDuckDuckGoWebView : WebView, DuckDuckGoWebView {
    private var lastClampedTopY: Boolean = true // when created we are always at the top
    private var contentAllowsSwipeToRefresh: Boolean = true
    private var enableSwipeRefreshCallback: ((Boolean) -> Unit)? = null
    private var hasGestureFinished = true
    private var canSwipeToRefresh = true

    private var lastY: Int = 0
    private var lastDeltaY: Int = 0
    private val scrollOffset = IntArray(2)
    private val scrollConsumed = IntArray(2)
    private var nestedOffsetY: Int = 0
    private var nestedScrollHelper: NestedScrollingChildHelper = NestedScrollingChildHelper(this)
    private val helper = CoordinatorLayoutHelper()

    override var isDestroyed: Boolean = false

    constructor(context: Context) : this(context, null)
    constructor(
        context: Context,
        attrs: AttributeSet?,
    ) : super(context, attrs) {
        isNestedScrollingEnabled = true
    }

    override fun getWebView(): WebView {
        return this
    }

    override fun safeCopyBackForwardList(): WebBackForwardList? {
        return (this as WebView).safeCopyBackForwardList()
    }

    override fun show() {
        (this as View).show()
    }

    override fun hide() {
        (this as View).hide()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        helper.onViewAttached(this)
    }

    override fun destroy() {
        isDestroyed = true
        super.destroy()
    }

    override fun setBottomMatchingBehaviourEnabled(value: Boolean) {
        helper.setBottomMatchingBehaviourEnabled(value)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val inputConnection = super.onCreateInputConnection(outAttrs) ?: return null

        addNoPersonalisedFlag(outAttrs)

        return inputConnection
    }

    private fun addNoPersonalisedFlag(outAttrs: EditorInfo) {
        outAttrs.imeOptions = outAttrs.imeOptions or IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        var returnValue = false

        val event = MotionEvent.obtain(ev)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            nestedOffsetY = 0
        }
        val eventY = event.y.toInt()
        event.offsetLocation(0f, nestedOffsetY.toFloat())

        when (action) {
            MotionEvent.ACTION_UP -> {
                hasGestureFinished = true
                returnValue = super.onTouchEvent(event)
                stopNestedScroll()
            }
            MotionEvent.ACTION_MOVE -> {
                var deltaY = lastY - eventY

                lastClampedTopY = deltaY <= 0

                if (dispatchNestedPreScroll(0, deltaY, scrollConsumed, scrollOffset)) {
                    deltaY -= scrollConsumed[1]
                    lastY = eventY - scrollOffset[1]
                    event.offsetLocation(0f, (-scrollOffset[1]).toFloat())
                    nestedOffsetY += scrollOffset[1]
                }

                returnValue = super.onTouchEvent(event)

                if (dispatchNestedScroll(0, scrollOffset[1], 0, deltaY, scrollOffset)) {
                    event.offsetLocation(0f, scrollOffset[1].toFloat())
                    nestedOffsetY += scrollOffset[1]
                    lastY -= scrollOffset[1]
                }

                lastDeltaY = deltaY
            }

            MotionEvent.ACTION_DOWN -> {
                hasGestureFinished = false
                // disable swipeRefresh until we can be sure it should be enabled
                enableSwipeRefresh(false)

                returnValue = super.onTouchEvent(event)
                lastY = eventY
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL)
            }

            else -> {
                returnValue = super.onTouchEvent(event)
                stopNestedScroll()
            }
        }

        return returnValue
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        nestedScrollHelper.isNestedScrollingEnabled = enabled
    }

    override fun stopNestedScroll() {
        nestedScrollHelper.stopNestedScroll()
    }

    override fun isNestedScrollingEnabled(): Boolean = nestedScrollHelper.isNestedScrollingEnabled
    override fun startNestedScroll(
        axes: Int,
        type: Int,
    ): Boolean = nestedScrollHelper.startNestedScroll(axes)

    override fun startNestedScroll(axes: Int): Boolean = nestedScrollHelper.startNestedScroll(axes)
    override fun stopNestedScroll(type: Int) {
        nestedScrollHelper.stopNestedScroll()
    }

    override fun hasNestedScrollingParent(): Boolean = nestedScrollHelper.hasNestedScrollingParent()
    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
        consumed: IntArray,
    ) {
        nestedScrollHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow)
    }

    override fun hasNestedScrollingParent(type: Int): Boolean = nestedScrollHelper.hasNestedScrollingParent()

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
    ): Boolean = nestedScrollHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow)

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
    ): Boolean =
        nestedScrollHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow)

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int,
    ): Boolean = nestedScrollHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
    ): Boolean =
        nestedScrollHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedFling(
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean,
    ): Boolean =
        nestedScrollHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(
        velocityX: Float,
        velocityY: Float,
    ): Boolean =
        nestedScrollHelper.dispatchNestedPreFling(velocityX, velocityY)

    override fun onOverScrolled(
        scrollX: Int,
        scrollY: Int,
        clampedX: Boolean,
        clampedY: Boolean,
    ) {
        // taking into account lastDeltaY since we are only interested whether we clamped at the top
        lastClampedTopY = clampedY && lastDeltaY <= 0

        if (!lastClampedTopY) {
            canSwipeToRefresh = false // disable because user scrolled down so we need a new gesture
        }

        if (lastClampedTopY && hasGestureFinished) {
            canSwipeToRefresh = true // only enable if at the top and gestured finished
        }

        enableSwipeRefresh(canSwipeToRefresh && clampedY && scrollY == 0 && (lastDeltaY <= 0 || nestedOffsetY == 0))
        post(helper::computeBottomMarginIfNeeded)
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
    }

    override fun setEnableSwipeRefreshCallback(callback: (Boolean) -> Unit) {
        enableSwipeRefreshCallback = callback
    }

    override fun removeEnableSwipeRefreshCallback() {
        enableSwipeRefreshCallback = null
    }

    private fun enableSwipeRefresh(enable: Boolean) {
        enableSwipeRefreshCallback?.invoke(enable && contentAllowsSwipeToRefresh)
    }

    private fun setContentAllowsSwipeToRefresh(allowed: Boolean) {
        contentAllowsSwipeToRefresh = allowed
        if (!allowed) {
            enableSwipeRefresh(false)
        }
    }

    override suspend fun isWebMessageListenerSupported(
        dispatchers: DispatcherProvider,
        webViewVersionProvider: WebViewVersionProvider,
    ): Boolean {
        return withContext(dispatchers.io()) {
            webViewVersionProvider.getFullVersion()
                .compareSemanticVersion(WEB_MESSAGE_LISTENER_WEBVIEW_VERSION)?.let { it >= 0 } ?: false
        } && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }

    @SuppressLint("RequiresFeature", "AddWebMessageListenerUsage")
    override suspend fun safeAddWebMessageListener(
        dispatchers: DispatcherProvider,
        webViewVersionProvider: WebViewVersionProvider,
        jsObjectName: String,
        allowedOriginRules: Set<String>,
        listener: WebMessageListener,
    ): Boolean = runCatching {
        if (isWebMessageListenerSupported(dispatchers, webViewVersionProvider) && !isDestroyed) {
            WebViewCompat.addWebMessageListener(
                this,
                jsObjectName,
                allowedOriginRules,
                listener,
            )
            true
        } else {
            false
        }
    }.getOrElse { exception ->
        Timber.e(exception, "Error adding WebMessageListener: $jsObjectName")
        false
    }

    @SuppressLint("RequiresFeature", "RemoveWebMessageListenerUsage")
    override suspend fun safeRemoveWebMessageListener(
        dispatchers: DispatcherProvider,
        webViewVersionProvider: WebViewVersionProvider,
        jsObjectName: String,
    ): Boolean = runCatching {
        if (isWebMessageListenerSupported(dispatchers, webViewVersionProvider) && !isDestroyed) {
            WebViewCompat.removeWebMessageListener(
                this,
                jsObjectName,
            )
            true
        } else {
            false
        }
    }.getOrElse { exception ->
        Timber.e(exception, "Error removing WebMessageListener: $jsObjectName")
        false
    }

    companion object {

        /*
         * Taken from EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
         * We can't use that value directly as it was only added on Oreo, but we can apply the value anyway.
         */
        private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000
        private const val WEB_MESSAGE_LISTENER_WEBVIEW_VERSION = "126.0.6478.40"
    }
}
