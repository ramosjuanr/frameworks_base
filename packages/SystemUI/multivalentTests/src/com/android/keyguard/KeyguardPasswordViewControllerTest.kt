/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.keyguard

import android.testing.TestableLooper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.util.LatencyTracker
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.Flags as AconfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class KeyguardPasswordViewControllerTest : SysuiTestCase() {
    @Mock private lateinit var keyguardPasswordView: KeyguardPasswordView
    @Mock private lateinit var passwordEntry: EditText
    @Mock lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock lateinit var securityMode: KeyguardSecurityModel.SecurityMode
    @Mock lateinit var lockPatternUtils: LockPatternUtils
    @Mock lateinit var keyguardSecurityCallback: KeyguardSecurityCallback
    @Mock lateinit var messageAreaControllerFactory: KeyguardMessageAreaController.Factory
    @Mock lateinit var latencyTracker: LatencyTracker
    @Mock lateinit var inputMethodManager: InputMethodManager
    @Mock lateinit var emergencyButtonController: EmergencyButtonController
    @Mock lateinit var mainExecutor: DelayableExecutor
    @Mock lateinit var falsingCollector: FalsingCollector
    @Mock lateinit var keyguardViewController: KeyguardViewController
    @Mock lateinit var mSelectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var mKeyguardMessageArea: BouncerKeyguardMessageArea
    @Mock
    private lateinit var mKeyguardMessageAreaController:
        KeyguardMessageAreaController<BouncerKeyguardMessageArea>
    @Mock private lateinit var postureController: DevicePostureController

    private lateinit var keyguardPasswordViewController: KeyguardPasswordViewController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        Mockito.`when`(
                keyguardPasswordView.requireViewById<BouncerKeyguardMessageArea>(
                    R.id.bouncer_message_area
                )
            )
            .thenReturn(mKeyguardMessageArea)
        Mockito.`when`(messageAreaControllerFactory.create(mKeyguardMessageArea))
            .thenReturn(mKeyguardMessageAreaController)
        Mockito.`when`(keyguardPasswordView.passwordTextViewId).thenReturn(R.id.passwordEntry)
        Mockito.`when`(keyguardPasswordView.findViewById<EditText>(R.id.passwordEntry))
            .thenReturn(passwordEntry)
        whenever(keyguardPasswordView.findViewById<ImageView>(R.id.switch_ime_button))
            .thenReturn(mock(ImageView::class.java))
        `when`(keyguardPasswordView.resources).thenReturn(context.resources)
        val fakeFeatureFlags = FakeFeatureFlags()
        fakeFeatureFlags.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, false)
        mSetFlagsRule.enableFlags(AconfigFlags.FLAG_REVAMPED_BOUNCER_MESSAGES)
        keyguardPasswordViewController =
            KeyguardPasswordViewController(
                keyguardPasswordView,
                keyguardUpdateMonitor,
                securityMode,
                lockPatternUtils,
                keyguardSecurityCallback,
                messageAreaControllerFactory,
                latencyTracker,
                inputMethodManager,
                emergencyButtonController,
                mainExecutor,
                mContext.resources,
                falsingCollector,
                keyguardViewController,
                postureController,
                fakeFeatureFlags,
                mSelectedUserInteractor,
            )
    }

    @Test
    fun testFocusWhenBouncerIsShown() {
        Mockito.`when`(keyguardViewController.isBouncerShowing).thenReturn(true)
        Mockito.`when`(keyguardPasswordView.isShown).thenReturn(true)
        keyguardPasswordViewController.onResume(KeyguardSecurityView.VIEW_REVEALED)
        keyguardPasswordView.post {
            verify(keyguardPasswordView).requestFocus()
            verify(keyguardPasswordView).showKeyboard()
        }
    }

    @Test
    fun testDoNotFocusWhenBouncerIsHidden() {
        Mockito.`when`(keyguardViewController.isBouncerShowing).thenReturn(false)
        Mockito.`when`(keyguardPasswordView.isShown).thenReturn(true)
        keyguardPasswordViewController.onResume(KeyguardSecurityView.VIEW_REVEALED)
        verify(keyguardPasswordView, never()).requestFocus()
    }

    @Test
    fun testHideKeyboardWhenOnPause() {
        keyguardPasswordViewController.onPause()
        keyguardPasswordView.post {
            verify(keyguardPasswordView).clearFocus()
            verify(keyguardPasswordView).hideKeyboard()
        }
    }

    @Test
    fun testOnViewAttached() {
        keyguardPasswordViewController.onViewAttached()
        verify(mKeyguardMessageAreaController)
            .setMessage(context.resources.getString(R.string.keyguard_enter_your_password), false)
    }

    @Test
    fun testOnViewAttached_withExistingMessage() {
        `when`(mKeyguardMessageAreaController.message).thenReturn("Unlock to continue.")
        keyguardPasswordViewController.onViewAttached()
        verify(mKeyguardMessageAreaController, never()).setMessage(anyString(), anyBoolean())
    }

    @Test
    fun testMessageIsSetWhenReset() {
        keyguardPasswordViewController.resetState()
        verify(mKeyguardMessageAreaController).setMessage(R.string.keyguard_enter_your_password)
    }
}
