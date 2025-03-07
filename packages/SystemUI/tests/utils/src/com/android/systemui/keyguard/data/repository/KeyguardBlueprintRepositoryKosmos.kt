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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.common.ui.data.repository.configurationRepository
import com.android.systemui.keyguard.shared.model.KeyguardBlueprint
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.blueprints.DefaultKeyguardBlueprint.Companion.DEFAULT
import com.android.systemui.kosmos.Kosmos

val Kosmos.keyguardBlueprintRepository by
    Kosmos.Fixture {
        KeyguardBlueprintRepository(
            configurationRepository = configurationRepository,
            blueprints = setOf(defaultBlueprint),
        )
    }

private val defaultBlueprint =
    object : KeyguardBlueprint {
        override val id: String
            get() = DEFAULT
        override val sections: List<KeyguardSection>
            get() = listOf()
    }
