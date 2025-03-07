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

package com.android.systemui.volume.panel.ui.composable

import android.content.res.Configuration.Orientation
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelState

class VolumePanelComposeScope(private val state: VolumePanelState) {

    /**
     * Layout orientation of the panel. It doesn't necessarily aligns with the device orientation,
     * because in some cases we want to show bigger version of a portrait orientation when the
     * device is in landscape.
     */
    @Orientation
    val orientation: Int
        get() = state.orientation
}
