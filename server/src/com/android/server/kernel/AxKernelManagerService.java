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

package com.android.server.kernel;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import com.android.internal.kernel.AxKernelControl;
import com.android.internal.kernel.AxKernelMetrics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Global hardware controls. Values and boot policy belong to the device owner. */
public final class AxKernelManagerService {
    private static final String TAG = "AxKernelManager";
    private static final String BOOT_KEY = "kernel_manager_apply_on_boot";
    private static final String VALUE_PREFIX = "kernel_manager_value_";
    private static AxKernelManagerService sInstance;
    private final Object mLock = new Object();
    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mHandler;
    private final ArrayMap<String, KernelControlNode> mControls = new ArrayMap<>();
    private final AxKernelMetricsReader mMetricsReader = new AxKernelMetricsReader();
    private boolean mReady;

    public static synchronized AxKernelManagerService getInstance(Context context) {
        if (sInstance == null) sInstance = new AxKernelManagerService(context);
        return sInstance;
    }

    private AxKernelManagerService(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    public void systemReady() {
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mContext.unregisterReceiver(this);
                mHandler.postDelayed(() -> initializeAfterVendorBoot(0), 2000);
            }
        }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, mHandler,
                Context.RECEIVER_NOT_EXPORTED);
    }

    private void initializeAfterVendorBoot(int attempt) {
        // Fairlady's kernel-post-boot sets governors and frequency bounds.
        // qcom-post-boot is a separate service; waiting for it alone races the
        // kernel setup. The parsed flag also detects a failed kernel script.
        String kernelState = android.os.SystemProperties.get("init.svc.kernel-post-boot", "");
        String qcomState = android.os.SystemProperties.get("init.svc.qcom-post-boot", "");
        boolean kernelReady = kernelState.isEmpty()
                || ("stopped".equals(kernelState)
                    && "1".equals(android.os.SystemProperties.get("vendor.post_boot.parsed", "")));
        boolean vendorReady = kernelReady
                && !"running".equals(qcomState) && !"restarting".equals(qcomState);
        if (!vendorReady && attempt < 30) {
            mHandler.postDelayed(() -> initializeAfterVendorBoot(attempt + 1), 1000);
            return;
        }
        if (!vendorReady) {
            Slog.w(TAG, "Vendor post-boot did not finish; skipping saved kernel values", null);
        }
        initializeControls(vendorReady);
    }

    private void initializeControls(boolean restoreSavedValues) {
        synchronized (mLock) {
            if (mReady) return;
            ArrayList<KernelControlNode> controls = new ArrayList<>();
            AxKernelMetricsReader.Config metrics = new AxKernelMetricsReader.Config();
            AxKernelConfigLoader.load(controls, metrics);
            for (KernelControlNode control : controls) {
                if (control.canUse()) mControls.put(control.id, control);
            }
            mMetricsReader.setConfig(metrics);
            mReady = true;
            if (restoreSavedValues && getApplyOnBootInternal()) {
                for (KernelControlNode control : mControls.values()) {
                    String raw = Settings.Secure.getStringForUser(mResolver,
                            VALUE_PREFIX + control.id, UserHandle.USER_SYSTEM);
                    if (raw == null) continue;
                    for (int value : control.availableValues) {
                        if (raw.equals(control.toFileValue(value))) {
                            applyLocked(control, value, false);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void enforceOwner() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER, TAG);
        int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != Process.ROOT_UID) {
            throw new SecurityException("Kernel controls are restricted to the system owner");
        }
    }

    public List<AxKernelControl> getControls() {
        enforceOwner();
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                ArrayList<AxKernelControl> result = new ArrayList<>();
                for (KernelControlNode node : mControls.values()) {
                    AxKernelControl control = node.snapshot(mResolver);
                    if (control != null) result.add(control);
                }
                return result;
            }
        } finally { Binder.restoreCallingIdentity(token); }
    }

    public boolean setControlValue(String id, int value) {
        enforceOwner();
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                KernelControlNode node = mControls.get(id);
                return node != null && node.canUse() && node.accepts(value)
                        && applyLocked(node, value);
            }
        } finally { Binder.restoreCallingIdentity(token); }
    }

    // The QTI HAL remains responsible for boost and thermal constraints. Do not
    // continually rewrite nodes or use Axion's unsupported Power HAL extension.
    private boolean applyLocked(KernelControlNode node, int value) {
        return applyLocked(node, value, true);
    }

    private boolean applyLocked(KernelControlNode node, int value, boolean persist) {
        KernelControlNode companion = companionLocked(node);
        int before = node.readValue();
        int companionBefore = companion != null ? companion.readValue() : 0;
        if (before == Integer.MIN_VALUE
                || (companion != null && companionBefore == Integer.MIN_VALUE)) return false;
        // devfreq uses 0 for an unrestricted bound, not a zero-Hz ceiling.
        int companionBound = isMin(node.type) && companionBefore == 0
                ? Integer.MAX_VALUE : companionBefore;
        boolean adjust = companion != null && (isMin(node.type)
                ? value > companionBound : value != 0 && value < companionBound);
        if (adjust && !companion.accepts(value)) return false;
        try {
            if (adjust) writeVerified(companion, value);
            writeVerified(node, value);
        } catch (IOException | IllegalArgumentException e) {
            Slog.w(TAG, "Failed to apply " + node.id, e);
            // Restore the original interval in an order accepted by cpufreq.
            restore(node, before);
            if (adjust) restore(companion, companionBefore);
            return false;
        }
        if (!persist) return true;
        boolean saved = save(node, value);
        if (adjust) saved &= save(companion, value);
        return saved;
    }

    private static void restore(KernelControlNode node, int value) {
        try { writeVerified(node, value); }
        catch (IOException | IllegalArgumentException e) {
            Slog.w(TAG, "Failed to restore " + node.id, e);
        }
    }

    private static void writeVerified(KernelControlNode node, int value) throws IOException {
        node.writeValue(value);
        // CPUFreq applies freq_qos changes from a scheduled worker. An immediate
        // read may still contain the previous policy bound. Write only once;
        // never fight thermal or Power HAL constraints by repeatedly writing.
        int actual = node.readValue();
        for (int attempt = 0; actual != value && attempt < 25; attempt++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted verifying " + node.id, e);
            }
            actual = node.readValue();
        }
        if (actual != value) {
            throw new IOException("Kernel did not accept " + node.id
                    + ": requested=" + value + ", actual=" + actual
                    + " after 500 ms");
        }
    }

    private boolean save(KernelControlNode node, int value) {
        // Store the raw governor/frequency, so governor ordering cannot change it.
        return Settings.Secure.putStringForUser(mResolver, VALUE_PREFIX + node.id,
                node.toFileValue(value), UserHandle.USER_SYSTEM);
    }

    private static boolean isMin(int type) {
        return type == AxKernelControl.TYPE_CPU_MIN_FREQ
                || type == AxKernelControl.TYPE_GPU_MIN_FREQ;
    }

    private KernelControlNode companionLocked(KernelControlNode node) {
        int target;
        switch (node.type) {
            case AxKernelControl.TYPE_CPU_MIN_FREQ: target = AxKernelControl.TYPE_CPU_MAX_FREQ; break;
            case AxKernelControl.TYPE_CPU_MAX_FREQ: target = AxKernelControl.TYPE_CPU_MIN_FREQ; break;
            case AxKernelControl.TYPE_GPU_MIN_FREQ: target = AxKernelControl.TYPE_GPU_MAX_FREQ; break;
            case AxKernelControl.TYPE_GPU_MAX_FREQ: target = AxKernelControl.TYPE_GPU_MIN_FREQ; break;
            default: return null;
        }
        for (KernelControlNode other : mControls.values()) {
            if (other.type == target && other.group.equals(node.group)) return other;
        }
        return null;
    }

    public AxKernelMetrics getMetrics(long activeTicks, long totalTicks) {
        enforceOwner();
        long token = Binder.clearCallingIdentity();
        try { return mMetricsReader.query(activeTicks, totalTicks); }
        finally { Binder.restoreCallingIdentity(token); }
    }

    private boolean getApplyOnBootInternal() {
        return Settings.Secure.getIntForUser(mResolver, BOOT_KEY, 0,
                UserHandle.USER_SYSTEM) != 0;
    }

    public boolean getApplyOnBoot() {
        enforceOwner();
        long token = Binder.clearCallingIdentity();
        try { return getApplyOnBootInternal(); }
        finally { Binder.restoreCallingIdentity(token); }
    }

    public boolean setApplyOnBoot(boolean enabled) {
        enforceOwner();
        long token = Binder.clearCallingIdentity();
        try {
            return Settings.Secure.putIntForUser(mResolver, BOOT_KEY, enabled ? 1 : 0,
                    UserHandle.USER_SYSTEM);
        } finally { Binder.restoreCallingIdentity(token); }
    }

    public boolean resetControls() {
        enforceOwner();
        long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                boolean success = Settings.Secure.putIntForUser(mResolver, BOOT_KEY, 0,
                        UserHandle.USER_SYSTEM);
                for (KernelControlNode node : mControls.values()) {
                    String key = VALUE_PREFIX + node.id;
                    if (Settings.Secure.getStringForUser(mResolver, key,
                            UserHandle.USER_SYSTEM) == null) continue;
                    if (applyLocked(node, node.defaultValue, false)) {
                        success &= Settings.Secure.putStringForUser(mResolver, key, null,
                                UserHandle.USER_SYSTEM);
                    } else { success = false; }
                }
                return success;
            }
        } finally { Binder.restoreCallingIdentity(token); }
    }
}
