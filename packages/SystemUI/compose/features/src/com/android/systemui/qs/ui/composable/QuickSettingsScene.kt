/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.ui.composable

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.TransitionState
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.footer.ui.compose.FooterActions
import com.android.systemui.qs.ui.viewmodel.QuickSettingsSceneViewModel
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.scene.ui.composable.toTransitionSceneKey
import com.android.systemui.shade.ui.composable.CollapsedShadeHeader
import com.android.systemui.shade.ui.composable.ExpandedShadeHeader
import com.android.systemui.shade.ui.composable.Shade
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.statusbar.phone.StatusBarIconController
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager
import com.android.systemui.statusbar.phone.StatusBarLocation
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** The Quick Settings (AKA "QS") scene shows the quick setting tiles. */
@SysUISingleton
class QuickSettingsScene
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: QuickSettingsSceneViewModel,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
) : ComposableScene {
    override val key = SceneKey.QuickSettings

    override val destinationScenes =
        viewModel.destinationScenes.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = emptyMap(),
        )

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        QuickSettingsScene(
            viewModel = viewModel,
            createTintedIconManager = tintedIconManagerFactory::create,
            createBatteryMeterViewController = batteryMeterViewControllerFactory::create,
            statusBarIconController = statusBarIconController,
            modifier = modifier,
        )
    }
}

@Composable
private fun SceneScope.QuickSettingsScene(
    viewModel: QuickSettingsSceneViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    modifier: Modifier = Modifier,
) {
    val cornerRadius by viewModel.notifications.cornerRadiusDp.collectAsState()

    // TODO(b/280887232): implement the real UI.
    Box(modifier = modifier.fillMaxSize()) {
        val isCustomizing by viewModel.qsSceneAdapter.isCustomizing.collectAsState()
        val collapsedHeaderHeight =
            with(LocalDensity.current) { ShadeHeader.Dimensions.CollapsedHeight.roundToPx() }
        val lifecycleOwner = LocalLifecycleOwner.current
        val footerActionsViewModel =
            remember(lifecycleOwner, viewModel) {
                viewModel.getFooterActionsViewModel(lifecycleOwner)
            }
        val scrollState = rememberScrollState()
        // When animating into the scene, we don't want it to be able to scroll, as it could mess
        // up with the expansion animation.
        val isScrollable =
            when (val state = layoutState.transitionState) {
                is TransitionState.Idle -> true
                is TransitionState.Transition -> {
                    state.fromScene == SceneKey.QuickSettings.toTransitionSceneKey()
                }
            }

        LaunchedEffect(isCustomizing, scrollState) {
            if (isCustomizing) {
                scrollState.scrollTo(0)
            }
        }

        // This is the background for the whole scene, as the elements don't necessarily provide
        // a background that extends to the edges.
        Spacer(
            modifier =
                Modifier.element(Shade.Elements.ScrimBackground)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim, shape = Shade.Shapes.Scrim)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.fillMaxSize()
                    // bottom should be tied to insets
                    .padding(bottom = 16.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                val shadeHeaderAndQuickSettingsModifier =
                    if (isCustomizing) {
                        Modifier.fillMaxHeight().align(Alignment.TopCenter)
                    } else {
                        Modifier.verticalNestedScrollToScene()
                            .verticalScroll(
                                scrollState,
                                enabled = isScrollable,
                            )
                            .clipScrollableContainer(Orientation.Horizontal)
                            .fillMaxWidth()
                            .wrapContentHeight(unbounded = true)
                            .align(Alignment.TopCenter)
                    }

                Column(
                    modifier = shadeHeaderAndQuickSettingsModifier,
                ) {
                    when (LocalWindowSizeClass.current.widthSizeClass) {
                        WindowWidthSizeClass.Compact ->
                            AnimatedVisibility(
                                visible = !isCustomizing,
                                enter =
                                    expandVertically(
                                        animationSpec = tween(100),
                                        initialHeight = { collapsedHeaderHeight },
                                    ) + fadeIn(tween(100)),
                                exit =
                                    shrinkVertically(
                                        animationSpec = tween(100),
                                        targetHeight = { collapsedHeaderHeight },
                                        shrinkTowards = Alignment.Top,
                                    ) + fadeOut(tween(100)),
                            ) {
                                ExpandedShadeHeader(
                                    viewModel = viewModel.shadeHeaderViewModel,
                                    createTintedIconManager = createTintedIconManager,
                                    createBatteryMeterViewController =
                                        createBatteryMeterViewController,
                                    statusBarIconController = statusBarIconController,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        else ->
                            CollapsedShadeHeader(
                                viewModel = viewModel.shadeHeaderViewModel,
                                createTintedIconManager = createTintedIconManager,
                                createBatteryMeterViewController = createBatteryMeterViewController,
                                statusBarIconController = statusBarIconController,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // This view has its own horizontal padding
                    QuickSettings(
                        modifier = Modifier.sysuiResTag("expanded_qs_scroll_view"),
                        viewModel.qsSceneAdapter,
                    )
                }
            }
            AnimatedVisibility(
                visible = !isCustomizing,
                modifier = Modifier.align(Alignment.CenterHorizontally).fillMaxWidth()
            ) {
                QuickSettingsTheme {
                    // This view has its own horizontal padding
                    // TODO(b/321716470) This should use a lifecycle tied to the scene.
                    FooterActions(
                        viewModel = footerActionsViewModel,
                        qsVisibilityLifecycleOwner = lifecycleOwner,
                        modifier = Modifier.element(QuickSettings.Elements.FooterActions)
                    )
                }
            }
        }
        // Scrim with height 0 aligned to bottom of the screen to facilitate shared element
        // transition from Shade scene.
        Box(
            modifier =
                Modifier.element(Notifications.Elements.NotificationScrim)
                    .fillMaxWidth()
                    .height(0.dp)
                    .graphicsLayer {
                        shape = RoundedCornerShape(cornerRadius.dp)
                        clip = true
                        alpha = 1f
                    }
                    .background(MaterialTheme.colorScheme.surface)
                    .align(Alignment.BottomCenter)
                    .onPlaced { coordinates: LayoutCoordinates ->
                        viewModel.notifications.onContentTopChanged(
                            coordinates.positionInWindow().y
                        )
                        val boundsInWindow = coordinates.boundsInWindow()
                        viewModel.notifications.onBoundsChanged(
                            left = boundsInWindow.left,
                            top = boundsInWindow.top,
                            right = boundsInWindow.right,
                            bottom = boundsInWindow.bottom,
                        )
                    }
        )
    }
}
