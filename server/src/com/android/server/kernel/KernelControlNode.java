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

import android.content.ContentResolver;
import android.os.FileUtils;
import android.text.TextUtils;

import com.android.internal.kernel.AxKernelControl;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

final class KernelControlNode {
    final String id;
    final String group;
    final int type;
    final String path;
    final int defaultValue;
    final int[] availableValues;
    final String[] valueLabels;
    final String[] writeValues;
    final String defaultRawValue;

    KernelControlNode(String id, String group, int type, String path, int defaultValue, int[] availableValues) {
        this(id, group, type, path, defaultValue, availableValues, new String[0], new String[0]);
    }

    KernelControlNode(String id, String group, int type, String path, int defaultValue,
            int[] availableValues, String[] valueLabels, String[] writeValues) {
        this.id = Objects.requireNonNull(id, "Control id must not be null");
        this.group = TextUtils.isEmpty(group) ? "Default" : group;
        this.type = type;
        this.path = Objects.requireNonNull(path, "Path must not be null");
        this.defaultValue = defaultValue;
        this.availableValues = availableValues != null ? availableValues : new int[0];
        this.valueLabels = valueLabels != null ? valueLabels : new String[0];
        this.writeValues = writeValues != null ? writeValues : new String[0];
        this.defaultRawValue = AxKernelUtils.readSysfsString(path);
    }

    boolean canUse() {
        File file = new File(path);
        try {
            return file.getCanonicalPath().startsWith("/sys/")
                    && file.canRead() && file.canWrite() && availableValues.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    boolean accepts(int value) {
        for (int candidate : availableValues) {
            if (candidate == value) return true;
        }
        return false;
    }

    String toFileValue(int value) {
        for (int i = 0; i < availableValues.length && i < writeValues.length; i++) {
            if (availableValues[i] == value) return writeValues[i];
        }
        if (value == defaultValue && !TextUtils.isEmpty(defaultRawValue)) return defaultRawValue;
        // Bounds may be changed by the kernel to a value outside the table.
        // Preserve its units when rolling back such an actual GPU bound.
        for (int i = 0; i < availableValues.length && i < writeValues.length; i++) {
            if (availableValues[i] <= 0) continue;
            try {
                long reference = Long.parseLong(writeValues[i]);
                if (reference > 0) return Long.toString((long) value * reference / availableValues[i]);
            } catch (NumberFormatException ignored) { }
        }
        return Integer.toString(value);
    }

    void writeValue(int value) throws IOException {
        FileUtils.stringToFile(path, toFileValue(value));
    }

    AxKernelControl snapshot(ContentResolver resolver) {
        if (!canUse() || TextUtils.isEmpty(id)) return null;
        return new AxKernelControl(id, group, type, readValue(), defaultValue,
                availableValues, valueLabels);
    }

    int readValue() {
        String val = AxKernelUtils.readSysfsString(path);
        for (int i = 0; i < writeValues.length && i < availableValues.length; i++) {
            if (writeValues[i].equals(val)) return availableValues[i];
        }
        if (val.equals(defaultRawValue) && !val.isEmpty()) return defaultValue;
        if (type == AxKernelControl.TYPE_CPU_GOVERNOR) return Integer.MIN_VALUE;
        try {
            long raw = Long.parseLong(val);
            for (int i = 0; i < availableValues.length && i < writeValues.length; i++) {
                if (availableValues[i] <= 0) continue;
                long reference = Long.parseLong(writeValues[i]);
                if (reference > 0) return Math.toIntExact(raw * availableValues[i] / reference);
            }
            return Math.toIntExact(raw);
        } catch (NumberFormatException | ArithmeticException e) {
            return Integer.MIN_VALUE;
        }
    }
}
