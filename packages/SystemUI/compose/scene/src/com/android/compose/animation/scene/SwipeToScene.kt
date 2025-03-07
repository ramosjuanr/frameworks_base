/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.compose.animation.scene

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize

/**
 * Configures the swipeable behavior of a [SceneTransitionLayout] depending on the current state.
 */
@Stable
internal fun Modifier.swipeToScene(gestureHandler: SceneGestureHandler): Modifier {
    return this.then(SwipeToSceneElement(gestureHandler))
}

private data class SwipeToSceneElement(
    val gestureHandler: SceneGestureHandler,
) : ModifierNodeElement<SwipeToSceneNode>() {
    override fun create(): SwipeToSceneNode = SwipeToSceneNode(gestureHandler)

    override fun update(node: SwipeToSceneNode) {
        node.gestureHandler = gestureHandler
    }
}

private class SwipeToSceneNode(
    gestureHandler: SceneGestureHandler,
) : DelegatingNode(), PointerInputModifierNode {
    private val delegate =
        delegate(
            MultiPointerDraggableNode(
                orientation = gestureHandler.orientation,
                enabled = ::enabled,
                startDragImmediately = ::startDragImmediately,
                onDragStarted = gestureHandler.draggable::onDragStarted,
                onDragDelta = gestureHandler.draggable::onDelta,
                onDragStopped = gestureHandler.draggable::onDragStopped,
            )
        )

    var gestureHandler: SceneGestureHandler = gestureHandler
        set(value) {
            if (value != field) {
                field = value

                // Make sure to update the delegate orientation. Note that this will automatically
                // reset the underlying pointer input handler, so previous gestures will be
                // cancelled.
                delegate.orientation = value.orientation
            }
        }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) = delegate.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = delegate.onCancelPointerInput()

    private fun enabled(): Boolean {
        return gestureHandler.isDrivingTransition ||
            currentScene().shouldEnableSwipes(gestureHandler.orientation)
    }

    private fun currentScene(): Scene {
        val layoutImpl = gestureHandler.layoutImpl
        return layoutImpl.scene(layoutImpl.state.transitionState.currentScene)
    }

    /** Whether swipe should be enabled in the given [orientation]. */
    private fun Scene.shouldEnableSwipes(orientation: Orientation): Boolean {
        return userActions.keys.any { it is Swipe && it.direction.orientation == orientation }
    }

    private fun startDragImmediately(startedPosition: Offset): Boolean {
        // Immediately start the drag if the user can't swipe in the other direction and the gesture
        // handler can intercept it.
        return !canOppositeSwipe() && gestureHandler.shouldImmediatelyIntercept(startedPosition)
    }

    private fun canOppositeSwipe(): Boolean {
        val oppositeOrientation =
            when (gestureHandler.orientation) {
                Orientation.Vertical -> Orientation.Horizontal
                Orientation.Horizontal -> Orientation.Vertical
            }
        return currentScene().shouldEnableSwipes(oppositeOrientation)
    }
}
