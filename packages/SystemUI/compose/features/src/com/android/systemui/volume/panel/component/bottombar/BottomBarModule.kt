/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.panel.component.bottombar

import com.android.systemui.volume.panel.component.bottombar.ui.BottomBarComponent
import com.android.systemui.volume.panel.component.shared.model.VolumePanelComponents
import com.android.systemui.volume.panel.domain.AlwaysAvailableCriteria
import com.android.systemui.volume.panel.domain.ComponentAvailabilityCriteria
import com.android.systemui.volume.panel.shared.model.VolumePanelUiComponent
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface BottomBarModule {

    @Binds
    @IntoMap
    @StringKey(VolumePanelComponents.BOTTOM_BAR)
    fun bindMediaVolumeSliderComponent(component: BottomBarComponent): VolumePanelUiComponent

    @Binds
    @IntoMap
    @StringKey(VolumePanelComponents.BOTTOM_BAR)
    fun bindComponentAvailabilityCriteria(
        defaultCriteria: AlwaysAvailableCriteria
    ): ComponentAvailabilityCriteria
}
