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

import android.os.FileUtils;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.kernel.AxKernelMetrics;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class AxKernelMetricsReader {
    private static final String TAG = "AxKernelMetricsReader";
    private static final String PROC_STAT = "/proc/stat";
    private static final int HZ_THRESHOLD = 10_000_000;

    private final Object mLock = new Object();
    private Config mConfig = new Config();

    static final class CpuConfig {
        final String id;
        final String group;
        final int[] cpuIds;
        final String currentPath;
        final String minPath;
        final String maxPath;
        final long frequencyMultiplier;

        CpuConfig(String id, String group, int[] cpuIds, String currentPath, String minPath, String maxPath, long frequencyMultiplier) {
            this.id = id;
            this.group = group;
            this.cpuIds = cpuIds;
            this.currentPath = currentPath;
            this.minPath = minPath;
            this.maxPath = maxPath;
            this.frequencyMultiplier = frequencyMultiplier;
        }
    }

    static final class GpuConfig {
        final String currentPath;
        final String minPath;
        final String maxPath;
        final String usagePath;
        final long frequencyMultiplier;

        GpuConfig(String currentPath, String minPath, String maxPath, String usagePath, long frequencyMultiplier) {
            this.currentPath = currentPath;
            this.minPath = minPath;
            this.maxPath = maxPath;
            this.usagePath = usagePath;
            this.frequencyMultiplier = frequencyMultiplier;
        }
    }

    static final class Config {
        final ArrayMap<String, CpuConfig> cpuConfigs = new ArrayMap<>();
        final ArrayList<GpuConfig> gpuConfigs = new ArrayList<>();

        void addCpu(CpuConfig cpu) {
            cpuConfigs.put(cpu.id, cpu);
        }

        void addGpu(GpuConfig gpu) {
            gpuConfigs.add(gpu);
        }
    }

    void setConfig(Config config) {
        synchronized (mLock) {
            mConfig = (config != null) ? config : new Config();
        }
    }

    AxKernelMetrics query(long prevActiveTicks, long prevTotalTicks) {
        Config config;
        synchronized (mLock) {
            config = mConfig;
        }

        long now = SystemClock.elapsedRealtime();
        CpuSample currentSample = readCpuSample();
        long totalActiveTicks = activeTimeTicks(currentSample != null ? currentSample.total : null);
        long totalTimeTicks = totalTimeTicks(currentSample != null ? currentSample.total : null);

        ArrayList<AxKernelMetrics.CpuCluster> cpuClusters = new ArrayList<>(config.cpuConfigs.size());
        for (int i = 0; i < config.cpuConfigs.size(); i++) {
            CpuConfig cpu = config.cpuConfigs.valueAt(i);
            CpuTimes times = sumTimes(currentSample, cpu.cpuIds);
            long curF = readFrequency(cpu.currentPath, cpu.frequencyMultiplier);
            long minF = readFrequency(cpu.minPath, cpu.frequencyMultiplier);
            long maxF = readFrequency(cpu.maxPath, cpu.frequencyMultiplier);
            long activeT = activeTimeTicks(times);
            long totalT = totalTimeTicks(times);
            cpuClusters.add(new AxKernelMetrics.CpuCluster(cpu.id, cpu.group, cpu.cpuIds, activeT, totalT, curF, minF, maxF));
        }

        AxKernelMetrics.Gpu gpu = sampleGpu(config.gpuConfigs);

        float usagePercent = cpuUsagePercent(prevActiveTicks, prevTotalTicks, totalActiveTicks, totalTimeTicks);
        return new AxKernelMetrics(now, usagePercent, totalActiveTicks, totalTimeTicks, cpuClusters, gpu);
    }

    private static float cpuUsagePercent(long prevActive, long prevTotal, long curActive, long curTotal) {
        if (prevActive < 0L || prevTotal <= 0L || curTotal <= 0L) return -1.0f;
        long deltaActive = curActive - prevActive;
        long deltaTotal = curTotal - prevTotal;
        if (deltaActive <= 0L || deltaTotal <= 0L) return 0.0f;
        float percent = (deltaActive * 100.0f) / deltaTotal;
        return Math.max(0.0f, Math.min(100.0f, percent));
    }

    private static CpuSample readCpuSample() {
        try {
            String text = FileUtils.readTextFile(new File(PROC_STAT), 2048, null);
            CpuTimes total = null;
            SparseArray<CpuTimes> cores = new SparseArray<>();
            for (String line : text.split("\n")) {
                if (!line.startsWith("cpu")) continue;
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 5) continue;
                if ("cpu".equals(tokens[0])) {
                    total = parseCpuTimes(tokens);
                } else if (tokens[0].startsWith("cpu")) {
                    int coreId = parseCoreId(tokens[0]);
                    if (coreId >= 0) {
                        cores.put(coreId, parseCpuTimes(tokens));
                    }
                }
            }
            return new CpuSample(total, cores);
        } catch (IOException e) {
            Slog.w(TAG, "Failed to read " + PROC_STAT, e);
            return null;
        }
    }

    private static int parseCoreId(String token) {
        try {
            return Integer.parseInt(token.substring(3));
        } catch (Exception e) {
            return -1;
        }
    }

    private static CpuTimes parseCpuTimes(String[] tokens) {
        long user = parseTick(tokens, 1);
        long nice = parseTick(tokens, 2);
        long sys = parseTick(tokens, 3);
        long idle = parseTick(tokens, 4);
        long iowait = parseTick(tokens, 5);
        long irq = parseTick(tokens, 6);
        long softirq = parseTick(tokens, 7);
        long steal = parseTick(tokens, 8);
        return new CpuTimes(user + nice + sys + idle + iowait + irq + softirq + steal, idle + iowait);
    }

    private static long parseTick(String[] tokens, int index) {
        if (index >= tokens.length) return 0L;
        String s = tokens[index];
        long val = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            val = val * 10 + (c - '0');
        }
        return val;
    }

    private static CpuTimes sumTimes(CpuSample sample, int[] cpuIds) {
        if (sample == null || sample.cores.size() == 0 || cpuIds == null || cpuIds.length == 0) {
            return sample != null ? sample.total : null;
        }
        long total = 0L;
        long idle = 0L;
        boolean matched = false;
        for (int id : cpuIds) {
            CpuTimes times = sample.cores.get(id);
            if (times != null) {
                total += times.total;
                idle += times.idle;
                matched = true;
            }
        }
        return matched ? new CpuTimes(total, idle) : sample.total;
    }

    private static long activeTimeTicks(CpuTimes times) {
        return times != null ? Math.max(0L, times.total - times.idle) : 0L;
    }

    private static long totalTimeTicks(CpuTimes times) {
        return times != null ? Math.max(0L, times.total) : 0L;
    }

    private static long readFrequency(String path, long multiplier) {
        long val = AxKernelUtils.readSysfsLong(path, 0L);
        if (val <= 0L) return 0L;
        if (val >= HZ_THRESHOLD) {
            return val;
        }
        return multiplier > 1L ? val * multiplier : val * 1000L;
    }

    private static int readBusy(String path) {
        String text = AxKernelUtils.readSysfsString(path);
        if (text.isEmpty()) return -1;
        String[] tokens = text.split("\\s+");
        if (tokens.length >= 2) {
            try {
                long busy = Long.parseLong(tokens[0]);
                long total = Long.parseLong(tokens[1]);
                if (busy >= 0 && total > 0L) {
                    return (int) Math.max(0L, Math.min(100L, (long) (busy * 100.0 / total)));
                }
                return -1;
            } catch (NumberFormatException e) { return -1; }
        }
        try { return Math.max(0, Math.min(100, Integer.parseInt(tokens[0]))); }
        catch (NumberFormatException e) { return -1; }
    }

    private static AxKernelMetrics.Gpu sampleGpu(List<GpuConfig> gpuConfigs) {
        long gpuFreq = 0L;
        long gpuMinFreq = 0L;
        long gpuMaxFreq = 0L;
        int gpuBusy = -1;
        for (int i = 0; i < gpuConfigs.size(); i++) {
            GpuConfig gpuConfig = gpuConfigs.get(i);
            if (gpuFreq == 0L) gpuFreq = readFrequency(gpuConfig.currentPath, gpuConfig.frequencyMultiplier);
            if (gpuMinFreq == 0L) gpuMinFreq = readFrequency(gpuConfig.minPath, gpuConfig.frequencyMultiplier);
            if (gpuMaxFreq == 0L) gpuMaxFreq = readFrequency(gpuConfig.maxPath, gpuConfig.frequencyMultiplier);
            if (gpuBusy < 0) gpuBusy = readBusy(gpuConfig.usagePath);
        }
        if (gpuFreq == 0L && gpuMaxFreq == 0L && gpuBusy < 0) return null;
        return new AxKernelMetrics.Gpu(gpuBusy, gpuFreq, gpuMinFreq, gpuMaxFreq);
    }

    private static final class CpuSample {
        final CpuTimes total;
        final SparseArray<CpuTimes> cores;

        CpuSample(CpuTimes total, SparseArray<CpuTimes> cores) {
            this.total = total;
            this.cores = cores;
        }
    }

    private static final class CpuTimes {
        final long total;
        final long idle;

        CpuTimes(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }
}
