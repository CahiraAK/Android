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

package com.duckduckgo.app.onboarding.ui.page

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckduckgo.anvil.annotations.ContributesViewModel
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.browser.omnibar.model.OmnibarPosition
import com.duckduckgo.app.global.DefaultRoleBrowserDialog
import com.duckduckgo.app.global.install.AppInstallStore
import com.duckduckgo.app.onboarding.ui.page.WelcomePage.Companion.PreOnboardingDialogType
import com.duckduckgo.app.onboarding.ui.page.WelcomePage.Companion.PreOnboardingDialogType.ADDRESS_BAR_POSITION
import com.duckduckgo.app.onboarding.ui.page.WelcomePage.Companion.PreOnboardingDialogType.CELEBRATION
import com.duckduckgo.app.onboarding.ui.page.WelcomePage.Companion.PreOnboardingDialogType.COMPARISON_CHART
import com.duckduckgo.app.onboarding.ui.page.WelcomePage.Companion.PreOnboardingDialogType.INITIAL
import com.duckduckgo.app.onboarding.ui.page.WelcomePageViewModel.Command.Finish
import com.duckduckgo.app.onboarding.ui.page.WelcomePageViewModel.Command.SetAddressBarPositionOptions
import com.duckduckgo.app.onboarding.ui.page.WelcomePageViewModel.Command.SetBackgroundResource
import com.duckduckgo.app.onboarding.ui.page.WelcomePageViewModel.Command.ShowAddressBarPositionDialog
import com.duckduckgo.app.onboarding.ui.page.WelcomePageViewModel.Command.ShowComparisonChart
import com.duckduckgo.app.onboarding.ui.page.WelcomePageViewModel.Command.ShowDefaultBrowserDialog
import com.duckduckgo.app.onboarding.ui.page.WelcomePageViewModel.Command.ShowSuccessDialog
import com.duckduckgo.app.onboarding.ui.page.extendedonboarding.HighlightsOnboardingExperimentManager
import com.duckduckgo.app.pixels.AppPixelName
import com.duckduckgo.app.pixels.AppPixelName.NOTIFICATION_RUNTIME_PERMISSION_SHOWN
import com.duckduckgo.app.pixels.AppPixelName.PREONBOARDING_ADDRESS_BAR_POSITION_SHOWN_UNIQUE
import com.duckduckgo.app.pixels.AppPixelName.PREONBOARDING_AFFIRMATION_SHOWN_UNIQUE
import com.duckduckgo.app.pixels.AppPixelName.PREONBOARDING_BOTTOM_ADDRESS_BAR_SELECTED_UNIQUE
import com.duckduckgo.app.pixels.AppPixelName.PREONBOARDING_CHOOSE_BROWSER_PRESSED
import com.duckduckgo.app.pixels.AppPixelName.PREONBOARDING_COMPARISON_CHART_SHOWN_UNIQUE
import com.duckduckgo.app.pixels.AppPixelName.PREONBOARDING_INTRO_SHOWN_UNIQUE
import com.duckduckgo.app.settings.db.SettingsDataStore
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.statistics.pixels.Pixel.PixelParameter
import com.duckduckgo.app.statistics.pixels.Pixel.PixelType.Unique
import com.duckduckgo.di.scopes.FragmentScope
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@SuppressLint("StaticFieldLeak")
@ContributesViewModel(FragmentScope::class)
class WelcomePageViewModel @Inject constructor(
    private val defaultRoleBrowserDialog: DefaultRoleBrowserDialog,
    private val context: Context,
    private val pixel: Pixel,
    private val appInstallStore: AppInstallStore,
    private val highlightsOnboardingExperimentManager: HighlightsOnboardingExperimentManager,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    private val _commands = Channel<Command>(1, DROP_OLDEST)
    val commands: Flow<Command> = _commands.receiveAsFlow()

    private var defaultAddressBarPosition: Boolean = true

    sealed interface Command {
        data object ShowComparisonChart : Command
        data class ShowDefaultBrowserDialog(val intent: Intent) : Command
        data object ShowSuccessDialog : Command
        data object ShowAddressBarPositionDialog : Command
        data object Finish : Command
        data class SetBackgroundResource(@DrawableRes val backgroundRes: Int) : Command
        data class SetAddressBarPositionOptions(val defaultOption: Boolean) : Command
    }

    fun onPrimaryCtaClicked(currentDialog: PreOnboardingDialogType) {
        when (currentDialog) {
            INITIAL -> {
                viewModelScope.launch {
                    _commands.send(ShowComparisonChart)
                }
            }

            COMPARISON_CHART -> {
                viewModelScope.launch {
                    val isDDGDefaultBrowser =
                        if (defaultRoleBrowserDialog.shouldShowDialog()) {
                            val intent = defaultRoleBrowserDialog.createIntent(context)
                            if (intent != null) {
                                _commands.send(ShowDefaultBrowserDialog(intent))
                            } else {
                                pixel.fire(AppPixelName.DEFAULT_BROWSER_DIALOG_NOT_SHOWN)
                                if (highlightsOnboardingExperimentManager.isHighlightsEnabled()) {
                                    _commands.send(ShowAddressBarPositionDialog)
                                } else {
                                    _commands.send(Finish)
                                }
                            }
                            false
                        } else {
                            _commands.send(Finish)
                            true
                        }
                    pixel.fire(
                        PREONBOARDING_CHOOSE_BROWSER_PRESSED,
                        mapOf(PixelParameter.DEFAULT_BROWSER to isDDGDefaultBrowser.toString()),
                    )
                }
            }

            ADDRESS_BAR_POSITION -> {
                if (!defaultAddressBarPosition) {
                    settingsDataStore.omnibarPosition = OmnibarPosition.BOTTOM
                    pixel.fire(PREONBOARDING_BOTTOM_ADDRESS_BAR_SELECTED_UNIQUE)
                }
                viewModelScope.launch {
                    _commands.send(Finish)
                }
            }

            CELEBRATION -> {
                viewModelScope.launch {
                    _commands.send(Finish)
                }
            }
        }
    }

    fun onDefaultBrowserSet() {
        defaultRoleBrowserDialog.dialogShown()
        appInstallStore.defaultBrowser = true
        pixel.fire(AppPixelName.DEFAULT_BROWSER_SET, mapOf(PixelParameter.DEFAULT_BROWSER_SET_FROM_ONBOARDING to true.toString()))

        viewModelScope.launch {
            if (highlightsOnboardingExperimentManager.isHighlightsEnabled()) {
                _commands.send(ShowAddressBarPositionDialog)
            } else {
                _commands.send(ShowSuccessDialog)
            }
        }
    }

    fun onDefaultBrowserNotSet() {
        defaultRoleBrowserDialog.dialogShown()
        appInstallStore.defaultBrowser = false
        pixel.fire(AppPixelName.DEFAULT_BROWSER_NOT_SET, mapOf(PixelParameter.DEFAULT_BROWSER_SET_FROM_ONBOARDING to true.toString()))

        viewModelScope.launch {
            if (highlightsOnboardingExperimentManager.isHighlightsEnabled()) {
                _commands.send(ShowAddressBarPositionDialog)
            } else {
                _commands.send(Finish)
            }
        }
    }

    fun notificationRuntimePermissionRequested() {
        pixel.fire(NOTIFICATION_RUNTIME_PERMISSION_SHOWN)
    }

    fun notificationRuntimePermissionGranted() {
        pixel.fire(
            AppPixelName.NOTIFICATIONS_ENABLED,
            mapOf(PixelParameter.FROM_ONBOARDING to true.toString()),
        )
    }

    fun onDialogShown(onboardingDialogType: PreOnboardingDialogType) {
        when (onboardingDialogType) {
            INITIAL -> pixel.fire(PREONBOARDING_INTRO_SHOWN_UNIQUE, type = Unique())
            COMPARISON_CHART -> pixel.fire(PREONBOARDING_COMPARISON_CHART_SHOWN_UNIQUE, type = Unique())
            ADDRESS_BAR_POSITION -> pixel.fire(PREONBOARDING_ADDRESS_BAR_POSITION_SHOWN_UNIQUE, type = Unique())
            CELEBRATION -> pixel.fire(PREONBOARDING_AFFIRMATION_SHOWN_UNIQUE, type = Unique())
        }
    }

    fun setBackgroundResource(lightModeEnabled: Boolean) {
        val backgroundRes = when {
            lightModeEnabled && highlightsOnboardingExperimentManager.isHighlightsEnabled() -> R.drawable.onboarding_experiment_background_bitmap_light
            !lightModeEnabled && highlightsOnboardingExperimentManager.isHighlightsEnabled() -> R.drawable.onboarding_experiment_background_bitmap_dark
            lightModeEnabled -> R.drawable.onboarding_background_bitmap_light
            else -> R.drawable.onboarding_background_bitmap_dark
        }
        viewModelScope.launch {
            _commands.send(SetBackgroundResource(backgroundRes))
        }
    }

    fun onAddressBarPositionOptionSelected(defaultOption: Boolean) {
        defaultAddressBarPosition = defaultOption
        viewModelScope.launch {
            _commands.send(SetAddressBarPositionOptions(defaultOption))
        }
    }
}
