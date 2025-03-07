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
package com.android.wm.shell.bubbles

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.Insets
import android.graphics.PointF
import android.graphics.Rect
import android.os.UserHandle
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.R
import com.android.wm.shell.bubbles.BubblePositioner.MAX_HEIGHT
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests operations and the resulting state managed by [BubblePositioner]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubblePositionerTest {

    private lateinit var positioner: BubblePositioner
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val defaultDeviceConfig =
        DeviceConfig(
            windowBounds = Rect(0, 0, 1000, 2000),
            isLargeScreen = false,
            isSmallTablet = false,
            isLandscape = false,
            isRtl = false,
            insets = Insets.of(0, 0, 0, 0)
        )

    @Before
    fun setUp() {
        val windowManager = context.getSystemService(WindowManager::class.java)
        positioner = BubblePositioner(context, windowManager)
    }

    @Test
    fun testUpdate() {
        val insets = Insets.of(10, 20, 5, 15)
        val screenBounds = Rect(0, 0, 1000, 1200)
        val availableRect = Rect(screenBounds)
        availableRect.inset(insets)
        positioner.update(defaultDeviceConfig.copy(insets = insets, windowBounds = screenBounds))
        assertThat(positioner.availableRect).isEqualTo(availableRect)
        assertThat(positioner.isLandscape).isFalse()
        assertThat(positioner.isLargeScreen).isFalse()
        assertThat(positioner.insets).isEqualTo(insets)
    }

    @Test
    fun testShowBubblesVertically_phonePortrait() {
        positioner.update(defaultDeviceConfig)
        assertThat(positioner.showBubblesVertically()).isFalse()
    }

    @Test
    fun testShowBubblesVertically_phoneLandscape() {
        positioner.update(defaultDeviceConfig.copy(isLandscape = true))
        assertThat(positioner.isLandscape).isTrue()
        assertThat(positioner.showBubblesVertically()).isTrue()
    }

    @Test
    fun testShowBubblesVertically_tablet() {
        positioner.update(defaultDeviceConfig.copy(isLargeScreen = true))
        assertThat(positioner.showBubblesVertically()).isTrue()
    }

    /** If a resting position hasn't been set, calling it will return the default position. */
    @Test
    fun testGetRestingPosition_returnsDefaultPosition() {
        positioner.update(defaultDeviceConfig)
        val restingPosition = positioner.getRestingPosition()
        val defaultPosition = positioner.defaultStartPosition
        assertThat(restingPosition).isEqualTo(defaultPosition)
    }

    /** If a resting position has been set, it'll return that instead of the default position. */
    @Test
    fun testGetRestingPosition_returnsRestingPosition() {
        positioner.update(defaultDeviceConfig)
        val restingPosition = PointF(100f, 100f)
        positioner.restingPosition = restingPosition
        assertThat(positioner.getRestingPosition()).isEqualTo(restingPosition)
    }

    /** Test that the default resting position on phone is in upper left. */
    @Test
    fun testGetRestingPosition_bubble_onPhone() {
        positioner.update(defaultDeviceConfig)
        val allowableStackRegion = positioner.getAllowableStackPositionRegion(1 /* bubbleCount */)
        val restingPosition = positioner.getRestingPosition()
        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.left)
        assertThat(restingPosition.y).isEqualTo(defaultYPosition)
    }

    @Test
    fun testGetRestingPosition_bubble_onPhone_RTL() {
        positioner.update(defaultDeviceConfig.copy(isRtl = true))
        val allowableStackRegion = positioner.getAllowableStackPositionRegion(1 /* bubbleCount */)
        val restingPosition = positioner.getRestingPosition()
        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.right)
        assertThat(restingPosition.y).isEqualTo(defaultYPosition)
    }

    /** Test that the default resting position on tablet is middle left. */
    @Test
    fun testGetRestingPosition_chatBubble_onTablet() {
        positioner.update(defaultDeviceConfig.copy(isLargeScreen = true))
        val allowableStackRegion = positioner.getAllowableStackPositionRegion(1 /* bubbleCount */)
        val restingPosition = positioner.getRestingPosition()
        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.left)
        assertThat(restingPosition.y).isEqualTo(defaultYPosition)
    }

    @Test
    fun testGetRestingPosition_chatBubble_onTablet_RTL() {
        positioner.update(defaultDeviceConfig.copy(isLargeScreen = true, isRtl = true))
        val allowableStackRegion = positioner.getAllowableStackPositionRegion(1 /* bubbleCount */)
        val restingPosition = positioner.getRestingPosition()
        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.right)
        assertThat(restingPosition.y).isEqualTo(defaultYPosition)
    }

    /** Test that the default resting position on tablet is middle right. */
    @Test
    fun testGetDefaultPosition_appBubble_onTablet() {
        positioner.update(defaultDeviceConfig.copy(isLargeScreen = true))
        val allowableStackRegion = positioner.getAllowableStackPositionRegion(1 /* bubbleCount */)
        val startPosition = positioner.getDefaultStartPosition(true /* isAppBubble */)
        assertThat(startPosition.x).isEqualTo(allowableStackRegion.right)
        assertThat(startPosition.y).isEqualTo(defaultYPosition)
    }

    @Test
    fun testGetRestingPosition_appBubble_onTablet_RTL() {
        positioner.update(defaultDeviceConfig.copy(isLargeScreen = true, isRtl = true))
        val allowableStackRegion = positioner.getAllowableStackPositionRegion(1 /* bubbleCount */)
        val startPosition = positioner.getDefaultStartPosition(true /* isAppBubble */)
        assertThat(startPosition.x).isEqualTo(allowableStackRegion.left)
        assertThat(startPosition.y).isEqualTo(defaultYPosition)
    }

    @Test
    fun testHasUserModifiedDefaultPosition_false() {
        positioner.update(defaultDeviceConfig.copy(isLargeScreen = true, isRtl = true))
        assertThat(positioner.hasUserModifiedDefaultPosition()).isFalse()
        positioner.restingPosition = positioner.defaultStartPosition
        assertThat(positioner.hasUserModifiedDefaultPosition()).isFalse()
    }

    @Test
    fun testHasUserModifiedDefaultPosition_true() {
        positioner.update(defaultDeviceConfig.copy(isLargeScreen = true, isRtl = true))
        assertThat(positioner.hasUserModifiedDefaultPosition()).isFalse()
        positioner.restingPosition = PointF(0f, 100f)
        assertThat(positioner.hasUserModifiedDefaultPosition()).isTrue()
    }

    @Test
    fun testGetExpandedViewHeight_max() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLargeScreen = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)
        val intent = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
        val bubble = Bubble.createAppBubble(intent, UserHandle(1), null, directExecutor())

        assertThat(positioner.getExpandedViewHeight(bubble)).isEqualTo(MAX_HEIGHT)
    }

    @Test
    fun testGetExpandedViewHeight_customHeight_valid() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLargeScreen = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)
        val minHeight =
            context.resources.getDimensionPixelSize(R.dimen.bubble_expanded_default_height)
        val bubble =
            Bubble(
                "key",
                ShortcutInfo.Builder(context, "id").build(),
                minHeight + 100 /* desiredHeight */,
                0 /* desiredHeightResId */,
                "title",
                0 /* taskId */,
                null /* locus */,
                true /* isDismissable */,
                directExecutor()) {}

        // Ensure the height is the same as the desired value
        assertThat(positioner.getExpandedViewHeight(bubble))
            .isEqualTo(bubble.getDesiredHeight(context))
    }

    @Test
    fun testGetExpandedViewHeight_customHeight_tooSmall() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLargeScreen = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        val bubble =
            Bubble(
                "key",
                ShortcutInfo.Builder(context, "id").build(),
                10 /* desiredHeight */,
                0 /* desiredHeightResId */,
                "title",
                0 /* taskId */,
                null /* locus */,
                true /* isDismissable */,
                directExecutor()) {}

        // Ensure the height is the same as the desired value
        val minHeight =
            context.resources.getDimensionPixelSize(R.dimen.bubble_expanded_default_height)
        assertThat(positioner.getExpandedViewHeight(bubble)).isEqualTo(minHeight)
    }

    @Test
    fun testGetMaxExpandedViewHeight_onLargeTablet() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLargeScreen = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        val manageButtonHeight =
            context.resources.getDimensionPixelSize(R.dimen.bubble_manage_button_height)
        val pointerWidth = context.resources.getDimensionPixelSize(R.dimen.bubble_pointer_width)
        val expandedViewPadding =
            context.resources.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding)
        val expectedHeight =
            1800 - 2 * 20 - manageButtonHeight - pointerWidth - expandedViewPadding * 2
        assertThat(positioner.getMaxExpandedViewHeight(false /* isOverflow */))
            .isEqualTo(expectedHeight)
    }

    @Test
    fun testAreBubblesBottomAligned_largeScreen_true() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLargeScreen = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        assertThat(positioner.areBubblesBottomAligned()).isTrue()
    }

    @Test
    fun testAreBubblesBottomAligned_largeScreen_landscape_false() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLargeScreen = true,
                isLandscape = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        assertThat(positioner.areBubblesBottomAligned()).isFalse()
    }

    @Test
    fun testAreBubblesBottomAligned_smallTablet_false() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLargeScreen = true,
                isSmallTablet = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        assertThat(positioner.areBubblesBottomAligned()).isFalse()
    }

    @Test
    fun testAreBubblesBottomAligned_phone_false() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        assertThat(positioner.areBubblesBottomAligned()).isFalse()
    }

    @Test
    fun testExpandedViewY_phoneLandscape() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLandscape = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        val intent = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
        val bubble = Bubble.createAppBubble(intent, UserHandle(1), null, directExecutor())

        // This bubble will have max height so it'll always be top aligned
        assertThat(positioner.getExpandedViewY(bubble, 0f /* bubblePosition */))
            .isEqualTo(positioner.getExpandedViewYTopAligned())
    }

    @Test
    fun testExpandedViewY_phonePortrait() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        val intent = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
        val bubble = Bubble.createAppBubble(intent, UserHandle(1), null, directExecutor())

        // Always top aligned in phone portrait
        assertThat(positioner.getExpandedViewY(bubble, 0f /* bubblePosition */))
            .isEqualTo(positioner.getExpandedViewYTopAligned())
    }

    @Test
    fun testExpandedViewY_smallTabletLandscape() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isSmallTablet = true,
                isLandscape = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        val intent = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
        val bubble = Bubble.createAppBubble(intent, UserHandle(1), null, directExecutor())

        // This bubble will have max height which is always top aligned on small tablets
        assertThat(positioner.getExpandedViewY(bubble, 0f /* bubblePosition */))
            .isEqualTo(positioner.getExpandedViewYTopAligned())
    }

    @Test
    fun testExpandedViewY_smallTabletPortrait() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isSmallTablet = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        val intent = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
        val bubble = Bubble.createAppBubble(intent, UserHandle(1), null, directExecutor())

        // This bubble will have max height which is always top aligned on small tablets
        assertThat(positioner.getExpandedViewY(bubble, 0f /* bubblePosition */))
            .isEqualTo(positioner.getExpandedViewYTopAligned())
    }

    @Test
    fun testExpandedViewY_largeScreenLandscape() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLargeScreen = true,
                isLandscape = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        val intent = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
        val bubble = Bubble.createAppBubble(intent, UserHandle(1), null, directExecutor())

        // This bubble will have max height which is always top aligned on landscape, large tablet
        assertThat(positioner.getExpandedViewY(bubble, 0f /* bubblePosition */))
            .isEqualTo(positioner.getExpandedViewYTopAligned())
    }

    @Test
    fun testExpandedViewY_largeScreenPortrait() {
        val deviceConfig =
            defaultDeviceConfig.copy(
                isLargeScreen = true,
                insets = Insets.of(10, 20, 5, 15),
                windowBounds = Rect(0, 0, 1800, 2600)
            )
        positioner.update(deviceConfig)

        val intent = Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
        val bubble = Bubble.createAppBubble(intent, UserHandle(1), null, directExecutor())

        val manageButtonHeight =
            context.resources.getDimensionPixelSize(R.dimen.bubble_manage_button_height)
        val manageButtonPlusMargin =
            manageButtonHeight +
                2 * context.resources.getDimensionPixelSize(R.dimen.bubble_manage_button_margin)
        val pointerWidth = context.resources.getDimensionPixelSize(R.dimen.bubble_pointer_width)

        val expectedExpandedViewY =
            positioner.availableRect.bottom -
                manageButtonPlusMargin -
                positioner.getExpandedViewHeightForLargeScreen() -
                pointerWidth

        // Bubbles are bottom aligned on portrait, large tablet
        assertThat(positioner.getExpandedViewY(bubble, 0f /* bubblePosition */))
            .isEqualTo(expectedExpandedViewY)
    }

    private val defaultYPosition: Float
        /**
         * Calculates the Y position bubbles should be placed based on the config. Based on the
         * calculations in [BubblePositioner.getDefaultStartPosition] and
         * [BubbleStackView.RelativeStackPosition].
         */
        get() {
            val isTablet = positioner.isLargeScreen

            // On tablet the position is centered, on phone it is an offset from the top.
            val desiredY =
                if (isTablet) {
                    positioner.screenRect.height() / 2f - positioner.bubbleSize / 2f
                } else {
                    context.resources
                        .getDimensionPixelOffset(R.dimen.bubble_stack_starting_offset_y)
                        .toFloat()
                }
            // Since we're visually centering the bubbles on tablet, use total screen height rather
            // than the available height.
            val height =
                if (isTablet) {
                    positioner.screenRect.height()
                } else {
                    positioner.availableRect.height()
                }
            val offsetPercent = (desiredY / height).coerceIn(0f, 1f)
            val allowableStackRegion =
                positioner.getAllowableStackPositionRegion(1 /* bubbleCount */)
            return allowableStackRegion.top + allowableStackRegion.height() * offsetPercent
        }
}
