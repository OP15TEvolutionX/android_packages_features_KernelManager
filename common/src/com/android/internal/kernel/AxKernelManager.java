/*
 * Copyright (C) 2025-2026 AxionOS
 * Adapted for Evolution X (C) 2026 Evolution X.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.internal.kernel;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.os.RemoteException;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** @hide */
public final class AxKernelManager {

    public AxKernelManager() {}

    @NonNull
    public static List<AxKernelControl> getControls() {
        try {
            List<AxKernelControl> controls = ActivityManager.getService().getAxKernelControls();
            return controls != null ? controls : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public static boolean setControlValue(@NonNull String id, int value) {
        try {
            return ActivityManager.getService().setAxKernelControlValue(Objects.requireNonNull(id), value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Nullable
    public static AxKernelMetrics getMetrics() {
        return getMetrics(AxKernelMetrics.CPU_TIME_UNAVAILABLE_TICKS, AxKernelMetrics.CPU_TIME_UNAVAILABLE_TICKS);
    }

    @Nullable
    public static AxKernelMetrics getMetrics(long previousCpuActiveTimeTicks, long previousCpuTimeTicks) {
        try {
            AxKernelMetrics metrics = ActivityManager.getService().getAxKernelMetrics(
                    previousCpuActiveTimeTicks, previousCpuTimeTicks);
            return metrics;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
    public static boolean getApplyOnBoot() {
        try { return ActivityManager.getService().getAxKernelApplyOnBoot(); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    public static boolean setApplyOnBoot(boolean enabled) {
        try { return ActivityManager.getService().setAxKernelApplyOnBoot(enabled); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    public static boolean resetControls() {
        try { return ActivityManager.getService().resetAxKernelControls(); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

}
