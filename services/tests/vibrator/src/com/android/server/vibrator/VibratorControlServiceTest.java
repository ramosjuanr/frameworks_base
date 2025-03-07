/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.vibrator;

import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_MEDIA;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
import static android.os.VibrationAttributes.USAGE_RINGTONE;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.VibrationAttributes.USAGE_UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.frameworks.vibrator.ScaleParam;
import android.frameworks.vibrator.VibrationParam;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.util.SparseArray;

import androidx.test.core.app.ApplicationProvider;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class VibratorControlServiceTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private VibrationScaler mMockVibrationScaler;
    @Mock
    private PackageManagerInternal mPackageManagerInternalMock;

    private FakeVibratorController mFakeVibratorController;
    private VibratorControlService mVibratorControlService;
    private VibrationSettings mVibrationSettings;
    private final Object mLock = new Object();

    @Before
    public void setUp() throws Exception {
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternalMock);

        TestLooper testLooper = new TestLooper();
        mVibrationSettings = new VibrationSettings(
                ApplicationProvider.getApplicationContext(), new Handler(testLooper.getLooper()));

        mFakeVibratorController = new FakeVibratorController();
        mVibratorControlService = new VibratorControlService(new VibratorControllerHolder(),
                mMockVibrationScaler, mVibrationSettings, mLock);
    }

    @Test
    public void testRegisterVibratorController() throws RemoteException {
        mVibratorControlService.registerVibratorController(mFakeVibratorController);

        assertThat(mFakeVibratorController.isLinkedToDeath).isTrue();
    }

    @Test
    public void testUnregisterVibratorController_providingTheRegisteredController_performsRequest()
            throws RemoteException {
        mVibratorControlService.registerVibratorController(mFakeVibratorController);
        mVibratorControlService.unregisterVibratorController(mFakeVibratorController);

        verify(mMockVibrationScaler).clearAdaptiveHapticsScales();
        assertThat(mFakeVibratorController.isLinkedToDeath).isFalse();
    }

    @Test
    public void testUnregisterVibratorController_providingAnInvalidController_ignoresRequest()
            throws RemoteException {
        FakeVibratorController fakeController1 = new FakeVibratorController();
        FakeVibratorController fakeController2 = new FakeVibratorController();
        mVibratorControlService.registerVibratorController(fakeController1);
        mVibratorControlService.unregisterVibratorController(fakeController2);

        verifyZeroInteractions(mMockVibrationScaler);
        assertThat(fakeController1.isLinkedToDeath).isTrue();
    }

    @Test
    public void testOnRequestVibrationParamsComplete_cachesAdaptiveHapticsScalesCorrectly()
            throws RemoteException {
        mVibratorControlService.registerVibratorController(mFakeVibratorController);
        int timeoutInMillis = 10;
        CompletableFuture<Void> future =
                mVibratorControlService.triggerVibrationParamsRequest(USAGE_RINGTONE,
                        timeoutInMillis);
        IBinder token = mVibratorControlService.getRequestVibrationParamsToken();

        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_ALARM, 0.7f);
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.onRequestVibrationParamsComplete(token,
                generateVibrationParams(vibrationScales));

        verify(mMockVibrationScaler).updateAdaptiveHapticsScale(USAGE_ALARM, 0.7f);
        verify(mMockVibrationScaler).updateAdaptiveHapticsScale(USAGE_NOTIFICATION, 0.4f);
        // Setting ScaleParam.TYPE_NOTIFICATION will update vibration scaling for both
        // notification and communication request usages.
        verify(mMockVibrationScaler).updateAdaptiveHapticsScale(USAGE_COMMUNICATION_REQUEST, 0.4f);
        verifyNoMoreInteractions(mMockVibrationScaler);

        assertThat(future.isDone()).isTrue();
        assertThat(future.isCompletedExceptionally()).isFalse();
    }

    @Test
    public void testOnRequestVibrationParamsComplete_withIncorrectToken_ignoresRequest()
            throws RemoteException, InterruptedException {
        mVibratorControlService.registerVibratorController(mFakeVibratorController);
        int timeoutInMillis = 10;
        CompletableFuture<Void> unusedFuture =
                mVibratorControlService.triggerVibrationParamsRequest(USAGE_RINGTONE,
                        timeoutInMillis);

        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_ALARM, 0.7f);
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.onRequestVibrationParamsComplete(new Binder(),
                generateVibrationParams(vibrationScales));

        verifyZeroInteractions(mMockVibrationScaler);
    }

    @Test
    public void testSetVibrationParams_cachesAdaptiveHapticsScalesCorrectly()
            throws RemoteException {
        mVibratorControlService.registerVibratorController(mFakeVibratorController);
        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_ALARM, 0.7f);
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.setVibrationParams(generateVibrationParams(vibrationScales),
                mFakeVibratorController);

        verify(mMockVibrationScaler).updateAdaptiveHapticsScale(USAGE_ALARM, 0.7f);
        verify(mMockVibrationScaler).updateAdaptiveHapticsScale(USAGE_NOTIFICATION, 0.4f);
        // Setting ScaleParam.TYPE_NOTIFICATION will update vibration scaling for both
        // notification and communication request usages.
        verify(mMockVibrationScaler).updateAdaptiveHapticsScale(USAGE_COMMUNICATION_REQUEST, 0.4f);
        verifyNoMoreInteractions(mMockVibrationScaler);
    }

    @Test
    public void testSetVibrationParams_withUnregisteredController_ignoresRequest()
            throws RemoteException {
        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_ALARM, 0.7f);
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.setVibrationParams(generateVibrationParams(vibrationScales),
                mFakeVibratorController);

        verifyZeroInteractions(mMockVibrationScaler);
    }

    @Test
    public void testClearVibrationParams_clearsCachedAdaptiveHapticsScales()
            throws RemoteException {
        mVibratorControlService.registerVibratorController(mFakeVibratorController);
        int types = buildVibrationTypesMask(ScaleParam.TYPE_ALARM, ScaleParam.TYPE_NOTIFICATION);

        mVibratorControlService.clearVibrationParams(types, mFakeVibratorController);

        verify(mMockVibrationScaler).removeAdaptiveHapticsScale(USAGE_ALARM);
        verify(mMockVibrationScaler).removeAdaptiveHapticsScale(USAGE_NOTIFICATION);
        // Clearing ScaleParam.TYPE_NOTIFICATION will clear vibration scaling for both
        // notification and communication request usages.
        verify(mMockVibrationScaler).removeAdaptiveHapticsScale(USAGE_COMMUNICATION_REQUEST);
    }

    @Test
    public void testClearVibrationParams_withUnregisteredController_ignoresRequest()
            throws RemoteException {
        mVibratorControlService.clearVibrationParams(ScaleParam.TYPE_ALARM,
                mFakeVibratorController);

        verifyZeroInteractions(mMockVibrationScaler);
    }

    @Test
    public void testRequestVibrationParams_createsFutureRequestProperly()
            throws RemoteException {
        int timeoutInMillis = 10;
        mVibratorControlService.registerVibratorController(mFakeVibratorController);
        CompletableFuture<Void> future =
                mVibratorControlService.triggerVibrationParamsRequest(USAGE_RINGTONE,
                        timeoutInMillis);
        try {
            future.orTimeout(timeoutInMillis, TimeUnit.MILLISECONDS).get();
        } catch (Throwable ignored) {
        }
        assertThat(mFakeVibratorController.didRequestVibrationParams).isTrue();
        assertThat(mFakeVibratorController.requestVibrationType).isEqualTo(
                ScaleParam.TYPE_RINGTONE);
        assertThat(mFakeVibratorController.requestTimeoutInMillis).isEqualTo(timeoutInMillis);
    }

    @Test
    public void testShouldRequestVibrationParams_returnsTrueForVibrationsThatShouldRequestParams()
            throws RemoteException {
        int[] vibrations =
                new int[]{USAGE_ALARM, USAGE_RINGTONE, USAGE_MEDIA, USAGE_TOUCH, USAGE_NOTIFICATION,
                        USAGE_HARDWARE_FEEDBACK, USAGE_UNKNOWN, USAGE_COMMUNICATION_REQUEST};
        mVibratorControlService.registerVibratorController(mFakeVibratorController);

        for (int vibration : vibrations) {
            assertThat(mVibratorControlService.shouldRequestVibrationParams(vibration))
                    .isEqualTo(ArrayUtils.contains(
                            mVibrationSettings.getRequestVibrationParamsForUsages(), vibration));
        }
    }

    @Test
    public void testShouldRequestVibrationParams_unregisteredVibratorController_returnsFalse()
            throws RemoteException {
        int[] vibrations =
                new int[]{USAGE_ALARM, USAGE_RINGTONE, USAGE_MEDIA, USAGE_TOUCH, USAGE_NOTIFICATION,
                        USAGE_HARDWARE_FEEDBACK, USAGE_UNKNOWN, USAGE_COMMUNICATION_REQUEST};

        for (int vibration : vibrations) {
            assertThat(mVibratorControlService.shouldRequestVibrationParams(vibration)).isFalse();
        }
    }

    private VibrationParam[] generateVibrationParams(SparseArray<Float> vibrationScales) {
        List<VibrationParam> vibrationParamList = new ArrayList<>();
        for (int i = 0; i < vibrationScales.size(); i++) {
            int type = vibrationScales.keyAt(i);
            float scale = vibrationScales.valueAt(i);

            vibrationParamList.add(generateVibrationParam(type, scale));
        }

        return vibrationParamList.toArray(new VibrationParam[0]);
    }

    private VibrationParam generateVibrationParam(int type, float scale) {
        ScaleParam scaleParam = new ScaleParam();
        scaleParam.typesMask = type;
        scaleParam.scale = scale;
        VibrationParam vibrationParam = new VibrationParam();
        vibrationParam.setScale(scaleParam);

        return vibrationParam;
    }

    private int buildVibrationTypesMask(int... types) {
        int typesMask = 0;
        for (int type : types) {
            typesMask |= type;
        }
        return typesMask;
    }
}
