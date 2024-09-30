/*
 * Copyright (c) 2024 DuckDuckGo
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

package com.duckduckgo.autofill.impl.importing.gpm.webflow

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewCompat
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.autofill.api.AutofillCapabilityChecker
import com.duckduckgo.autofill.api.AutofillFragmentResultsPlugin
import com.duckduckgo.autofill.api.BrowserAutofill
import com.duckduckgo.autofill.api.CredentialAutofillDialogFactory
import com.duckduckgo.autofill.api.domain.app.LoginCredentials
import com.duckduckgo.autofill.api.domain.app.LoginTriggerType
import com.duckduckgo.autofill.impl.R
import com.duckduckgo.autofill.impl.databinding.FragmentImportGooglePasswordsWebflowBinding
import com.duckduckgo.autofill.impl.importing.CsvPasswordImporter
import com.duckduckgo.autofill.impl.importing.CsvPasswordImporter.ParseResult.Error
import com.duckduckgo.autofill.impl.importing.CsvPasswordImporter.ParseResult.Success
import com.duckduckgo.autofill.impl.importing.PasswordImporter
import com.duckduckgo.autofill.impl.importing.gpm.webflow.ImportGooglePasswordsWebFlowFragment.Companion.Result.Companion.RESULT_KEY
import com.duckduckgo.autofill.impl.importing.gpm.webflow.ImportGooglePasswordsWebFlowFragment.Companion.Result.Companion.RESULT_KEY_DETAILS
import com.duckduckgo.autofill.impl.importing.gpm.webflow.ImportGooglePasswordsWebFlowViewModel.ViewState.NavigatingBack
import com.duckduckgo.autofill.impl.importing.gpm.webflow.ImportGooglePasswordsWebFlowViewModel.ViewState.ShowingWebContent
import com.duckduckgo.autofill.impl.importing.gpm.webflow.ImportGooglePasswordsWebFlowViewModel.ViewState.UserCancelledImportFlow
import com.duckduckgo.autofill.impl.importing.gpm.webflow.ImportGooglePasswordsWebFlowWebChromeClient.ProgressListener
import com.duckduckgo.autofill.impl.importing.gpm.webflow.ImportGooglePasswordsWebFlowWebViewClient.NewPageCallback
import com.duckduckgo.autofill.impl.importing.gpm.webflow.autofill.NoOpAutofillCallback
import com.duckduckgo.autofill.impl.importing.gpm.webflow.autofill.NoOpAutofillEventListener
import com.duckduckgo.autofill.impl.importing.gpm.webflow.autofill.NoOpEmailProtectionInContextSignupFlowListener
import com.duckduckgo.autofill.impl.importing.gpm.webflow.autofill.NoOpEmailProtectionUserPromptListener
import com.duckduckgo.common.ui.DuckDuckGoFragment
import com.duckduckgo.common.ui.viewbinding.viewBinding
import com.duckduckgo.common.utils.ConflatedJob
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.FragmentViewModelFactory
import com.duckduckgo.common.utils.plugins.PluginPoint
import com.duckduckgo.di.scopes.FragmentScope
import com.duckduckgo.user.agent.api.UserAgentProvider
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import timber.log.Timber

@InjectWith(FragmentScope::class)
class ImportGooglePasswordsWebFlowFragment :
    DuckDuckGoFragment(R.layout.fragment_import_google_passwords_webflow),
    ProgressListener,
    NewPageCallback,
    NoOpAutofillCallback,
    NoOpEmailProtectionInContextSignupFlowListener,
    NoOpEmailProtectionUserPromptListener,
    NoOpAutofillEventListener,
    GooglePasswordBlobConsumer.Callback {

    @Inject
    lateinit var userAgentProvider: UserAgentProvider

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var pixel: Pixel

    @Inject
    lateinit var viewModelFactory: FragmentViewModelFactory

    @Inject
    lateinit var autofillCapabilityChecker: AutofillCapabilityChecker

    @Inject
    lateinit var credentialAutofillDialogFactory: CredentialAutofillDialogFactory

    @Inject
    lateinit var browserAutofill: BrowserAutofill

    @Inject
    lateinit var autofillFragmentResultListeners: PluginPoint<AutofillFragmentResultsPlugin>

    @Inject
    lateinit var passwordBlobConsumer: GooglePasswordBlobConsumer

    @Inject
    lateinit var passwordImporterScriptLoader: PasswordImporterScriptLoader

    @Inject
    lateinit var csvPasswordImporter: CsvPasswordImporter

    @Inject
    lateinit var browserAutofillConfigurator: BrowserAutofill.Configurator

    @Inject
    lateinit var passwordImporter: PasswordImporter

    val viewModel by lazy {
        ViewModelProvider(requireActivity(), viewModelFactory)[ImportGooglePasswordsWebFlowViewModel::class.java]
    }

    private val autofillConfigurationJob = ConflatedJob()

    private val binding: FragmentImportGooglePasswordsWebflowBinding by viewBinding()

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        initialiseToolbar()
        configureWebView()
        configureBackButtonHandler()
        observeViewState()
        loadFirstWebpage(activity?.intent)
    }

    private fun loadFirstWebpage(intent: Intent?) {
        lifecycleScope.launch(dispatchers.main()) {
            autofillConfigurationJob.join()

            binding.webView.loadUrl(STARTING_URL)

            viewModel.loadedStartingUrl()
        }
    }

    private fun observeViewState() {
        lifecycleScope.launch(dispatchers.main()) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.viewState.collect { viewState ->
                    when (viewState) {
                        //     is ViewState.CancellingInContextSignUp -> cancelInContextSignUp()
                        //     is ViewState.ConfirmingCancellationOfInContextSignUp -> confirmCancellationOfInContextSignUp()
                        //     is ViewState.NavigatingBack -> navigateWebViewBack()
                        //     is ViewState.ShowingWebContent -> showWebContent(viewState)
                        //     is ViewState.ExitingAsSuccess -> closeActivityAsSuccessfulSignup()
                        is ShowingWebContent -> {} // TODO()
                        is UserCancelledImportFlow -> exitFlowAsCancellation(viewState.stage)
                        is NavigatingBack -> binding.webView.goBack()
                    }
                }
            }
        }
    }

    private fun exitFlowAsCancellation(stage: String) {
        (activity as ImportGooglePasswordsWebFlowActivity).exitUserCancelled(stage)
    }

    private fun configureBackButtonHandler() {
        activity?.let {
            it.onBackPressedDispatcher.addCallback(
                it,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        viewModel.onBackButtonPressed(url = binding.webView.url, canGoBack = binding.webView.canGoBack())
                    }
                },
            )
        }
    }

    private fun initialiseToolbar() {
        with(getToolbar()) {
            title = getString(R.string.autofillImportGooglePasswordsWebFlowTitle)
            setNavigationIconAsCross()
            setNavigationOnClickListener { viewModel.onCloseButtonPressed(binding.webView.url) }
        }
    }

    private fun Toolbar.setNavigationIconAsCross() {
        setNavigationIcon(com.duckduckgo.mobile.android.R.drawable.ic_close_24)
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun configureWebView() {
        Timber.i("cdr Configuring WebView")
        binding.webView.let { webView ->
            webView.webChromeClient = ImportGooglePasswordsWebFlowWebChromeClient(this)
            webView.webViewClient = ImportGooglePasswordsWebFlowWebViewClient(this)

            webView.settings.apply {
                userAgentString = userAgentProvider.userAgent()
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setSupportMultipleWindows(true)
                databaseEnabled = false
                setSupportZoom(true)
            }

            configureDownloadInterceptor(webView)
            configureAutofill(webView)

            lifecycleScope.launch {
                passwordBlobConsumer.configureWebViewForBlobDownload(webView, this@ImportGooglePasswordsWebFlowFragment)
                configurePasswordImportJavascript(webView)
            }
        }
    }

    private fun configureAutofill(it: WebView) {
        lifecycleScope.launch {
            browserAutofill.addJsInterface(
                it,
                this@ImportGooglePasswordsWebFlowFragment,
                this@ImportGooglePasswordsWebFlowFragment,
                this@ImportGooglePasswordsWebFlowFragment,
                CUSTOM_FLOW_TAB_ID,
            )
        }

        autofillFragmentResultListeners.getPlugins().forEach { plugin ->
            setFragmentResultListener(plugin.resultKey(CUSTOM_FLOW_TAB_ID)) { _, result ->
                context?.let { ctx ->
                    plugin.processResult(
                        result = result,
                        context = ctx,
                        tabId = CUSTOM_FLOW_TAB_ID,
                        fragment = this@ImportGooglePasswordsWebFlowFragment,
                        autofillCallback = this@ImportGooglePasswordsWebFlowFragment,
                    )
                }
            }
        }
    }

    private fun configureDownloadInterceptor(it: WebView) {
        it.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (url.startsWith("blob:")) {
                // if (isBlobDownloadWebViewFeatureEnabled) {
                lifecycleScope.launch {
                    passwordBlobConsumer.postMessageToConvertBlobToDataUri(url)
                }
            }
        }
    }

    private suspend fun configurePasswordImportJavascript(webView: WebView) {
        val script = passwordImporterScriptLoader.getScript()
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
    }

    private fun getToolbar() = (activity as ImportGooglePasswordsWebFlowActivity).binding.includeToolbar.toolbar

    override fun onPageStarted(url: String?) {
        browserAutofillConfigurator.configureAutofillForCurrentPage(binding.webView, url)
        viewModel.onPageStarted(url)
    }

    override fun onPageFinished(url: String?) {
        viewModel.onPageFinished(url)
    }

    override suspend fun onCredentialsAvailableToInject(
        originalUrl: String,
        credentials: List<LoginCredentials>,
        triggerType: LoginTriggerType,
    ) {
        Timber.i("cdr Credentials available to autofill (%d creds available)", credentials.size)
        withContext(dispatchers.main()) {
            val url = binding.webView.url ?: return@withContext
            if (url != originalUrl) {
                Timber.w("WebView url has changed since autofill request; bailing")
                return@withContext
            }

            val dialog = credentialAutofillDialogFactory.autofillSelectCredentialsDialog(
                url,
                credentials,
                triggerType,
                CUSTOM_FLOW_TAB_ID,
            )
            dialog.show(childFragmentManager, SELECT_CREDENTIALS_FRAGMENT_TAG)
        }
    }

    override suspend fun onCsvAvailable(csv: String) {
        Timber.i("cdr CSV available %s", csv)
        when (val parseResult = csvPasswordImporter.readCsv(csv)) {
            is Success -> {
                onCsvParsed(parseResult)
            }
            is Error -> {
                onCsvError()
            }
        }

        /**
         *  val result = csvPasswordImporter.importCsv(csv)
         *         val resultDetails = when (result) {
         *             is Success -> {
         *                 Timber.i("cdr Found %d passwords; Imported %d passwords", result.numberPasswordsInSource, result.passwordIdsImported.size)
         *                 Result.Success(foundInImport = result.numberPasswordsInSource, importedCount = result.passwordIdsImported.size)
         *             }
         *
         *             ImportResult.Error -> Result.Error
         *         }
         *         val resultBundle = Bundle().also { it.putParcelable(RESULT_KEY_DETAILS, resultDetails) }
         *
         */
    }

    private suspend fun onCsvParsed(parseResult: Success) {
        val importResult = passwordImporter.importPasswords(parseResult.loginCredentialsToImport)
        val resultBundle = Bundle().also {
            it.putParcelable(
                RESULT_KEY_DETAILS,
                Result.Success(importResult.savedCredentialIds.size, importResult.duplicatedPasswords.size),
            )
        }
        setFragmentResult(RESULT_KEY, resultBundle)
    }

    override suspend fun onCsvError() {
        Timber.e("cdr Error decoding CSV")
        val resultBundle = Bundle().also {
            it.putParcelable(RESULT_KEY_DETAILS, Result.Error)
        }
        setFragmentResult(RESULT_KEY, resultBundle)
    }

    override fun onShareCredentialsForAutofill(
        originalUrl: String,
        selectedCredentials: LoginCredentials,
    ) {
        if (binding.webView.url != originalUrl) {
            Timber.w("WebView url has changed since autofill request; bailing")
            return
        }
        browserAutofill.injectCredentials(selectedCredentials)
    }

    override fun onNoCredentialsChosenForAutofill(originalUrl: String) {
        if (binding.webView.url != originalUrl) {
            Timber.w("WebView url has changed since autofill request; bailing")
            return
        }
        browserAutofill.injectCredentials(null)
    }

    companion object {
        private const val STARTING_URL = "https://passwords.google.com/options?ep=1"
        private const val CUSTOM_FLOW_TAB_ID = "import-passwords-webflow"
        private const val SELECT_CREDENTIALS_FRAGMENT_TAG = "autofillSelectCredentialsDialog"

        sealed interface Result : Parcelable {

            companion object {
                const val RESULT_KEY = "importResult"
                const val RESULT_KEY_DETAILS = "importResultDetails"
            }

            @Parcelize
            data class Success(val importedCount: Int, val foundInImport: Int) : Result

            @Parcelize
            data class UserCancelled(val stage: String) : Result

            @Parcelize
            data object Error : Result
        }
    }
}
