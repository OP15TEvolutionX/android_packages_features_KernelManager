/*
 * Copyright (C) 2025-2026 AxionOS
 *
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

import android.os.Environment;
import android.text.TextUtils;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.kernel.AxKernelControl;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.server.kernel.AxKernelMetricsReader;
import com.android.server.kernel.AxKernelUtils;
import com.android.server.kernel.KernelControlNode;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class AxKernelConfigLoader {
    private static final String TAG = "AxKernelConfigLoader";
    private static final String CONFIG_NAME = "ax_kernel_manager.xml";
    private static final String DIR_ODM_ETC = "/odm/etc";
    private static final String DIR_SYSTEM_ETC = "/system/etc";
    private static final String SUBDIR_ETC = "etc";

    private static final String DIR_SYS_CPU = "/sys/devices/system/cpu";
    private static final String DIR_CPUFREQ = "/sys/devices/system/cpu/cpufreq";
    private static final String DIR_KGSL = "/sys/class/kgsl/kgsl-3d0";
    private static final String DIR_DEVFREQ = "/sys/class/devfreq";

    private static final String NODE_CPUFREQ = "cpufreq";
    private static final String NODE_POLICY_PREFIX = "policy";

    private static final String NODE_SCALING_MIN_FREQ = "scaling_min_freq";
    private static final String NODE_SCALING_MAX_FREQ = "scaling_max_freq";
    private static final String NODE_SCALING_CUR_FREQ = "scaling_cur_freq";
    private static final String NODE_CPUINFO_CUR_FREQ = "cpuinfo_cur_freq";
    private static final String NODE_CPUINFO_MIN_FREQ = "cpuinfo_min_freq";
    private static final String NODE_CPUINFO_MAX_FREQ = "cpuinfo_max_freq";
    private static final String NODE_SCALING_AVAIL_FREQS = "scaling_available_frequencies";
    private static final String NODE_SCALING_BOOST_FREQS = "scaling_boost_frequencies";
    private static final String NODE_SCALING_GOVERNOR = "scaling_governor";
    private static final String NODE_SCALING_AVAIL_GOVERNORS = "scaling_available_governors";
    private static final String NODE_AFFECTED_CPUS = "affected_cpus";
    private static final String NODE_RELATED_CPUS = "related_cpus";

    private static final String NODE_DEVFREQ_CUR_FREQ = "devfreq/cur_freq";
    private static final String NODE_DEVFREQ_MAX_FREQ = "devfreq/max_freq";
    private static final String NODE_DEVFREQ_MIN_FREQ = "devfreq/min_freq";
    private static final String NODE_DEVFREQ_AVAIL_FREQS = "devfreq/available_frequencies";
    private static final String NODE_KGSL_GPUCLK = "gpuclk";
    private static final String NODE_KGSL_MAX_GPUCLK = "max_gpuclk";
    private static final String NODE_KGSL_MIN_GPUCLK = "min_gpuclk";
    private static final String NODE_KGSL_AVAIL_FREQS = "gpu_available_frequencies";
    private static final String NODE_KGSL_BUSY = "gpubusy";
    private static final String NODE_LOAD = "load";
    private static final String NODE_CUR_FREQ = "cur_freq";
    private static final String NODE_MAX_FREQ = "max_freq";
    private static final String NODE_MIN_FREQ = "min_freq";
    private static final String NODE_AVAILABLE_FREQUENCIES = "available_frequencies";

    private static final String KEYWORD_GPU = "gpu";
    private static final String KEYWORD_MALI = "mali";
    private static final String KEYWORD_KGSL = "kgsl";

    private static final String TAG_CPU = "cpu";
    private static final String TAG_GPU = "gpu";

    private static final String ATTR_ID = "id";
    private static final String ATTR_GROUP = "group";
    private static final String ATTR_MIN_NODE = "minNode";
    private static final String ATTR_MAX_NODE = "maxNode";
    private static final String ATTR_CUR_FREQ_PATH = "curFreqPath";
    private static final String ATTR_AVAILABLE_PATH = "availablePath";
    private static final String ATTR_GOVERNOR_NODE = "governorNode";
    private static final String ATTR_GOVERNOR_AVAIL_PATH = "governorAvailablePath";
    private static final String ATTR_BUSY_PATH = "busyPath";
    private static final String ATTR_CPU_IDS = "cpuIds";
    private static final String ATTR_NODE = "node";
    private static final String ATTR_CURRENT_NODE = "currentNode";
    private static final String ATTR_USAGE_NODE = "usageNode";
    private static final String ATTR_VALUES = "values";
    private static final String ATTR_FREQ_MULTIPLIER = "frequencyMultiplier";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_PATH = "path";
    private static final String ATTR_CPU = "cpu";

    private static final String GROUP_GPU = "GPU";
    private static final String GROUP_CPU_PREFIX = "CPU (";
    private static final String GROUP_CPU_SUFFIX = ")";

    private static final String SUFFIX_MIN_FREQ = "_min_freq";
    private static final String SUFFIX_MAX_FREQ = "_max_freq";
    private static final String SUFFIX_GOVERNOR = "_governor";
    private static final String ID_POLICY_PREFIX = "policy";
    private static final String ID_GPU_MIN_FREQ = "axion_gpu_min_freq";
    private static final String ID_GPU_MAX_FREQ = "axion_gpu_max_freq";

    private static final int DEFAULT_GOV_INDEX = 0;
    private static final int DEFAULT_VALUE_ZERO = 0;
    private static final long MULTIPLIER_CPU_HZ = 1000L;
    private static final long MULTIPLIER_DIRECT = 1L;

    private static final File[] CONFIG_FILES = {
            new File(DIR_ODM_ETC, CONFIG_NAME),
            Environment.buildPath(Environment.getVendorDirectory(), SUBDIR_ETC, CONFIG_NAME),
            Environment.buildPath(Environment.getSystemExtDirectory(), SUBDIR_ETC, CONFIG_NAME),
            new File(DIR_SYSTEM_ETC, CONFIG_NAME),
    };

    private AxKernelConfigLoader() {
    }

    static void load(ArrayList<KernelControlNode> controls, AxKernelMetricsReader.Config metricsConfig) {
        for (File file : CONFIG_FILES) {
            if (file.isFile()) {
                parseConfigFile(file, controls, metricsConfig);
                if (hasCpuControls(controls) || hasGpuControls(controls)) {
                    break;
                }
            }
        }
        if (!hasCpuControls(controls)) {
            findCpuFallback(controls, metricsConfig);
        }
        if (!hasGpuControls(controls)) {
            findGpuFallback(controls, metricsConfig);
        }
    }

    private static void parseConfigFile(File file, ArrayList<KernelControlNode> controls, AxKernelMetricsReader.Config metricsConfig) {
        try (FileInputStream stream = new FileInputStream(file)) {
            readConfig(Xml.resolvePullParser(stream), controls, metricsConfig);
        } catch (IOException | XmlPullParserException e) {
            Slog.w(TAG, "Failed to read " + file, e);
        }
    }

    private static boolean hasCpuControls(List<KernelControlNode> controls) {
        for (int i = 0; i < controls.size(); i++) {
            int t = controls.get(i).type;
            if (t == AxKernelControl.TYPE_CPU_MIN_FREQ || t == AxKernelControl.TYPE_CPU_MAX_FREQ || t == AxKernelControl.TYPE_CPU_GOVERNOR) return true;
        }
        return false;
    }

    private static boolean hasGpuControls(List<KernelControlNode> controls) {
        for (int i = 0; i < controls.size(); i++) {
            int t = controls.get(i).type;
            if (t == AxKernelControl.TYPE_GPU_MIN_FREQ || t == AxKernelControl.TYPE_GPU_MAX_FREQ) return true;
        }
        return false;
    }

    private static void readConfig(TypedXmlPullParser parser, ArrayList<KernelControlNode> controls, AxKernelMetricsReader.Config metricsConfig)
            throws IOException, XmlPullParserException {
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (TAG_CPU.equals(tag)) parseCpu(parser, controls, metricsConfig);
            if (TAG_GPU.equals(tag)) parseGpu(parser, controls, metricsConfig);
        }
    }

    private static String resolveCpuId(String id, String name, String node, int index) {
        if (!TextUtils.isEmpty(id)) {
            return id;
        }
        if (!TextUtils.isEmpty(name)) {
            return name.toLowerCase(Locale.ROOT);
        }
        if (!TextUtils.isEmpty(node) && node.contains(NODE_POLICY_PREFIX)) {
            return node.substring(node.lastIndexOf('/') + 1);
        }
        return "cpu" + index;
    }

    private static void parseCpu(TypedXmlPullParser parser, ArrayList<KernelControlNode> controls, AxKernelMetricsReader.Config metricsConfig) {
        String node = getAttr(parser, ATTR_NODE, ATTR_PATH);
        String name = parser.getAttributeValue(null, ATTR_NAME);
        String id = resolveCpuId(parser.getAttributeValue(null, ATTR_ID), name, node, controls.size());
        String group = attrOrDefault(parser, ATTR_GROUP, !TextUtils.isEmpty(name) ? name : id);
        String minId = attrOrDefault(parser, "minId", id + SUFFIX_MIN_FREQ);
        String maxId = attrOrDefault(parser, "maxId", id + SUFFIX_MAX_FREQ);
        String govId = attrOrDefault(parser, "governorId", id + SUFFIX_GOVERNOR);
        String minPath = getAttr(parser, ATTR_MIN_NODE, "minPath");
        String maxPath = getAttr(parser, ATTR_MAX_NODE, "maxPath");
        String curPath = getAttr(parser, ATTR_CUR_FREQ_PATH, "currentPath");
        String availPath = getAttr(parser, ATTR_AVAILABLE_PATH, "availableNode");
        String govPath = getAttr(parser, ATTR_GOVERNOR_NODE, "governorPath");
        String govAvail = getAttr(parser, ATTR_GOVERNOR_AVAIL_PATH, "governorAvailableNode");

        if (node != null) {
            if (minPath == null) minPath = node + "/" + NODE_SCALING_MIN_FREQ;
            if (maxPath == null) maxPath = node + "/" + NODE_SCALING_MAX_FREQ;
            if (curPath == null) curPath = node + "/" + NODE_SCALING_CUR_FREQ;
            if (availPath == null) availPath = node + "/" + NODE_SCALING_AVAIL_FREQS;
            if (govPath == null) govPath = node + "/" + NODE_SCALING_GOVERNOR;
            if (govAvail == null) govAvail = node + "/" + NODE_SCALING_AVAIL_GOVERNORS;
        }

        int[] freqs = AxKernelUtils.readSysfsIntList(availPath);
        if (freqs.length > 0 && canUse(minPath) && canUse(maxPath)) {
            int defMin = AxKernelUtils.readSysfsInt(minPath, freqs[0]);
            int defMax = AxKernelUtils.readSysfsInt(maxPath, freqs[freqs.length - 1]);
            String[] labels = AxKernelUtils.formatFrequencyLabels(freqs);
            String[] writeValues = AxKernelUtils.toStringValues(freqs);
            controls.add(new KernelControlNode(minId, group, AxKernelControl.TYPE_CPU_MIN_FREQ, minPath, defMin, freqs, labels, writeValues));
            controls.add(new KernelControlNode(maxId, group, AxKernelControl.TYPE_CPU_MAX_FREQ, maxPath, defMax, freqs, labels, writeValues));
        }

        String[] govs = AxKernelUtils.readSysfsTokens(govAvail);
        if (govs.length > 0 && canUse(govPath)) {
            int defGov = stringIndex(govs, AxKernelUtils.readSysfsString(govPath), DEFAULT_GOV_INDEX);
            int[] indices = indexedValues(govs.length);
            controls.add(new KernelControlNode(govId, group, AxKernelControl.TYPE_CPU_GOVERNOR, govPath, defGov, indices, govs, govs));
        }

        int[] cpuIds = AxKernelUtils.parseCpuIds(getAttr(parser, ATTR_CPU_IDS, ATTR_CPU));
        metricsConfig.addCpu(new AxKernelMetricsReader.CpuConfig(id, group, cpuIds, curPath, minPath, maxPath, MULTIPLIER_CPU_HZ));
    }

    private static void parseGpu(TypedXmlPullParser parser, ArrayList<KernelControlNode> controls, AxKernelMetricsReader.Config metricsConfig) {
        String id = parser.getAttributeValue(null, ATTR_ID);
        if (TextUtils.isEmpty(id)) {
            id = "gpu";
        }
        String group = attrOrDefault(parser, ATTR_GROUP, GROUP_GPU);
        String minId = attrOrDefault(parser, "minId", ID_GPU_MIN_FREQ);
        String maxId = attrOrDefault(parser, "maxId", ID_GPU_MAX_FREQ);

        String baseNode = parser.getAttributeValue(null, ATTR_NODE);
        String minPath = resolveGpuPath(parser, ATTR_MIN_NODE, "minPath", baseNode, NODE_MIN_FREQ);
        String maxPath = resolveGpuPath(parser, ATTR_MAX_NODE, "maxPath", baseNode, NODE_MAX_FREQ);
        String curPath = resolveGpuPath(parser, ATTR_CURRENT_NODE, ATTR_CUR_FREQ_PATH, baseNode, NODE_CUR_FREQ);
        String busyPath = resolveGpuPath(parser, ATTR_USAGE_NODE, ATTR_BUSY_PATH, baseNode, NODE_LOAD);

        long multiplier = parseMultiplier(parser);
        int[] freqs = resolveGpuFrequencies(parser, baseNode);
        if (freqs.length > 0 && canUse(minPath) && canUse(maxPath)) {
            int rawDefMin = AxKernelUtils.readSysfsInt(minPath, freqs[0]);
            int rawDefMax = AxKernelUtils.readSysfsInt(maxPath, freqs[freqs.length - 1]);
            int[] availableKhz = AxKernelUtils.toKhzList(freqs, multiplier);
            String[] writeValues = AxKernelUtils.toStringValues(freqs);
            String[] labels = AxKernelUtils.formatFrequencyLabels(availableKhz);
            int defMinKhz = AxKernelUtils.toKhz(rawDefMin, multiplier);
            int defMaxKhz = AxKernelUtils.toKhz(rawDefMax, multiplier);
            controls.add(new KernelControlNode(minId, group, AxKernelControl.TYPE_GPU_MIN_FREQ, minPath, defMinKhz, availableKhz, labels, writeValues));
            controls.add(new KernelControlNode(maxId, group, AxKernelControl.TYPE_GPU_MAX_FREQ, maxPath, defMaxKhz, availableKhz, labels, writeValues));
        }

        metricsConfig.addGpu(new AxKernelMetricsReader.GpuConfig(curPath, minPath, maxPath, busyPath, multiplier));
    }

    private static String attrOrDefault(TypedXmlPullParser parser, String attr, String defaultValue) {
        String val = parser.getAttributeValue(null, attr);
        return !TextUtils.isEmpty(val) ? val : defaultValue;
    }

    private static String resolveGpuPath(TypedXmlPullParser parser, String primary, String fallback, String baseNode, String subNode) {
        String path = getAttr(parser, primary, fallback);
        if (!TextUtils.isEmpty(path)) return path;
        return !TextUtils.isEmpty(baseNode) ? baseNode + "/" + subNode : null;
    }

    private static int[] resolveGpuFrequencies(TypedXmlPullParser parser, String baseNode) {
        String values = getAttr(parser, ATTR_VALUES, "availableValues");
        if (!TextUtils.isEmpty(values)) return AxKernelUtils.parseCpuIds(values);
        String availPath = getAttr(parser, ATTR_AVAILABLE_PATH, "availableNode");
        if (TextUtils.isEmpty(availPath) && !TextUtils.isEmpty(baseNode)) {
            availPath = baseNode + "/" + NODE_AVAILABLE_FREQUENCIES;
        }
        return AxKernelUtils.readSysfsIntList(availPath);
    }

    private static long parseMultiplier(TypedXmlPullParser parser) {
        String mult = getAttr(parser, ATTR_FREQ_MULTIPLIER, "frequencymultiplier");
        if (TextUtils.isEmpty(mult)) return MULTIPLIER_DIRECT;
        int val = AxKernelUtils.extractNumber(mult);
        return val > 0 ? val : MULTIPLIER_DIRECT;
    }

    private static String getAttr(TypedXmlPullParser parser, String primary, String fallback) {
        String val = parser.getAttributeValue(null, primary);
        return !TextUtils.isEmpty(val) ? val : parser.getAttributeValue(null, fallback);
    }

    private static List<File> findPolicyDirs() {
        List<File> policyDirs = new ArrayList<>();
        File[] files = new File(DIR_CPUFREQ).listFiles();
        if (files != null) {
            Arrays.stream(files).filter(f -> f.isDirectory() && f.getName().startsWith(NODE_POLICY_PREFIX)).forEach(policyDirs::add);
        }
        if (!policyDirs.isEmpty()) {
            return policyDirs;
        }
        File[] cpuFiles = new File(DIR_SYS_CPU).listFiles();
        if (cpuFiles != null) {
            Arrays.stream(cpuFiles).map(f -> new File(f, NODE_CPUFREQ)).filter(File::isDirectory).filter(d -> !policyDirs.contains(d)).forEach(policyDirs::add);
        }
        return policyDirs;
    }

    private static void findCpuFallback(ArrayList<KernelControlNode> controls, AxKernelMetricsReader.Config metricsConfig) {
        List<File> policyDirs = findPolicyDirs();
        policyDirs.sort((a, b) -> Integer.compare(AxKernelUtils.extractNumber(a.getName()), AxKernelUtils.extractNumber(b.getName())));

        int clusterIndex = DEFAULT_VALUE_ZERO;
        for (File policyDir : policyDirs) {
            int policyIndex = AxKernelUtils.extractNumber(policyDir.getName());
            if (policyIndex < 0) policyIndex = clusterIndex;

            int[] cpuIds = readCpuIds(policyDir, policyIndex);
            String group = GROUP_CPU_PREFIX + AxKernelUtils.formatCpuRange(cpuIds) + GROUP_CPU_SUFFIX;

            int[] frequencies = readPolicyFrequencies(policyDir);
            addPolicyFreqControls(policyIndex, group, policyDir, frequencies, controls);
            addPolicyGovControls(policyIndex, group, policyDir, controls);

            String curFreqPath = resolveCurFreqPath(policyDir);
            String minPath = new File(policyDir, NODE_SCALING_MIN_FREQ).getAbsolutePath();
            String maxPath = new File(policyDir, NODE_SCALING_MAX_FREQ).getAbsolutePath();
            metricsConfig.addCpu(new AxKernelMetricsReader.CpuConfig(ID_POLICY_PREFIX + policyIndex, group, cpuIds, curFreqPath, minPath, maxPath, MULTIPLIER_CPU_HZ));
            clusterIndex++;
        }
    }

    private static int[] readPolicyFrequencies(File policyDir) {
        int[] frequencies = AxKernelUtils.readSysfsIntList(new File(policyDir, NODE_SCALING_AVAIL_FREQS).getAbsolutePath());
        if (frequencies.length > 0) return frequencies;
        frequencies = AxKernelUtils.readSysfsIntList(new File(policyDir, NODE_SCALING_BOOST_FREQS).getAbsolutePath());
        if (frequencies.length > 0) return frequencies;
        return readFallbackFrequencies(policyDir);
    }

    private static void addPolicyFreqControls(int policyIndex, String group, File policyDir, int[] freqs, List<KernelControlNode> controls) {
        if (freqs.length == 0) return;
        String minPath = new File(policyDir, NODE_SCALING_MIN_FREQ).getAbsolutePath();
        String maxPath = new File(policyDir, NODE_SCALING_MAX_FREQ).getAbsolutePath();
        int defMin = AxKernelUtils.readSysfsInt(minPath, freqs[0]);
        int defMax = AxKernelUtils.readSysfsInt(maxPath, freqs[freqs.length - 1]);
        String[] labels = AxKernelUtils.formatFrequencyLabels(freqs);
        String[] writeValues = AxKernelUtils.toStringValues(freqs);
        controls.add(new KernelControlNode(ID_POLICY_PREFIX + policyIndex + SUFFIX_MIN_FREQ, group, AxKernelControl.TYPE_CPU_MIN_FREQ, minPath, defMin, freqs, labels, writeValues));
        controls.add(new KernelControlNode(ID_POLICY_PREFIX + policyIndex + SUFFIX_MAX_FREQ, group, AxKernelControl.TYPE_CPU_MAX_FREQ, maxPath, defMax, freqs, labels, writeValues));
    }

    private static void addPolicyGovControls(int policyIndex, String group, File policyDir, List<KernelControlNode> controls) {
        File availGovFile = new File(policyDir, NODE_SCALING_AVAIL_GOVERNORS);
        String[] governors = AxKernelUtils.readSysfsTokens(availGovFile.getAbsolutePath());
        if (governors.length == 0) return;
        String govPath = new File(policyDir, NODE_SCALING_GOVERNOR).getAbsolutePath();
        int defGov = stringIndex(governors, AxKernelUtils.readSysfsString(govPath), DEFAULT_GOV_INDEX);
        int[] indices = indexedValues(governors.length);
        controls.add(new KernelControlNode(ID_POLICY_PREFIX + policyIndex + SUFFIX_GOVERNOR, group, AxKernelControl.TYPE_CPU_GOVERNOR, govPath, defGov, indices, governors, governors));
    }

    private static String resolveCurFreqPath(File policyDir) {
        File scalingCur = new File(policyDir, NODE_SCALING_CUR_FREQ);
        return scalingCur.exists() ? scalingCur.getAbsolutePath() : new File(policyDir, NODE_CPUINFO_CUR_FREQ).getAbsolutePath();
    }

    private static int[] readFallbackFrequencies(File policyDir) {
        int minF = AxKernelUtils.readSysfsInt(new File(policyDir, NODE_CPUINFO_MIN_FREQ).getAbsolutePath(), DEFAULT_VALUE_ZERO);
        int maxF = AxKernelUtils.readSysfsInt(new File(policyDir, NODE_CPUINFO_MAX_FREQ).getAbsolutePath(), DEFAULT_VALUE_ZERO);
        if (minF <= 0 || maxF <= 0 || minF > maxF) return new int[0];
        return minF == maxF ? new int[] { minF } : new int[] { minF, maxF };
    }

    private static void addGpuControlsIfPresent(File minFreq, File maxFreq, int[] freqs, List<KernelControlNode> controls, long multiplier) {
        if (freqs.length == 0 || !minFreq.isFile() || !maxFreq.isFile()) return;
        int rawDefMin = AxKernelUtils.readSysfsInt(minFreq.getAbsolutePath(), freqs[0]);
        int rawDefMax = AxKernelUtils.readSysfsInt(maxFreq.getAbsolutePath(), freqs[freqs.length - 1]);
        int[] availableKhz = AxKernelUtils.toKhzList(freqs, multiplier);
        String[] writeValues = AxKernelUtils.toStringValues(freqs);
        String[] labels = AxKernelUtils.formatFrequencyLabels(availableKhz);
        int defMinKhz = AxKernelUtils.toKhz(rawDefMin, multiplier);
        int defMaxKhz = AxKernelUtils.toKhz(rawDefMax, multiplier);
        controls.add(new KernelControlNode(ID_GPU_MIN_FREQ, GROUP_GPU, AxKernelControl.TYPE_GPU_MIN_FREQ, minFreq.getAbsolutePath(), defMinKhz, availableKhz, labels, writeValues));
        controls.add(new KernelControlNode(ID_GPU_MAX_FREQ, GROUP_GPU, AxKernelControl.TYPE_GPU_MAX_FREQ, maxFreq.getAbsolutePath(), defMaxKhz, availableKhz, labels, writeValues));
    }

    private static void findGpuFallback(ArrayList<KernelControlNode> controls, AxKernelMetricsReader.Config metricsConfig) {
        File kgslDir = new File(DIR_KGSL);
        if (kgslDir.isDirectory()) {
            File curFreq = new File(kgslDir, NODE_DEVFREQ_CUR_FREQ);
            if (!curFreq.isFile()) curFreq = new File(kgslDir, NODE_KGSL_GPUCLK);
            File maxFreq = new File(kgslDir, NODE_DEVFREQ_MAX_FREQ);
            if (!maxFreq.isFile()) maxFreq = new File(kgslDir, NODE_KGSL_MAX_GPUCLK);
            File minFreq = new File(kgslDir, NODE_DEVFREQ_MIN_FREQ);
            if (!minFreq.isFile()) minFreq = new File(kgslDir, NODE_KGSL_MIN_GPUCLK);
            File availFreq = new File(kgslDir, NODE_DEVFREQ_AVAIL_FREQS);
            if (!availFreq.isFile()) availFreq = new File(kgslDir, NODE_KGSL_AVAIL_FREQS);
            File busy = new File(kgslDir, NODE_KGSL_BUSY);

            int[] freqs = AxKernelUtils.readSysfsIntList(availFreq.getAbsolutePath());
            addGpuControlsIfPresent(minFreq, maxFreq, freqs, controls, MULTIPLIER_DIRECT);
            metricsConfig.addGpu(new AxKernelMetricsReader.GpuConfig(
                    curFreq.isFile() ? curFreq.getAbsolutePath() : null,
                    minFreq.isFile() ? minFreq.getAbsolutePath() : null,
                    maxFreq.isFile() ? maxFreq.getAbsolutePath() : null,
                    busy.isFile() ? busy.getAbsolutePath() : null,
                    MULTIPLIER_DIRECT));
            return;
        }

        File devfreqDir = new File(DIR_DEVFREQ);
        if (devfreqDir.isDirectory()) {
            File[] files = devfreqDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().toLowerCase();
                    if (name.contains(KEYWORD_GPU) || name.contains(KEYWORD_MALI) || name.contains(KEYWORD_KGSL)) {
                        File curFreq = new File(file, NODE_CUR_FREQ);
                        File maxFreq = new File(file, NODE_MAX_FREQ);
                        File minFreq = new File(file, NODE_MIN_FREQ);
                        File availFreq = new File(file, NODE_AVAILABLE_FREQUENCIES);
                        File load = new File(file, NODE_LOAD);

                        int[] freqs = AxKernelUtils.readSysfsIntList(availFreq.getAbsolutePath());
                        addGpuControlsIfPresent(minFreq, maxFreq, freqs, controls, MULTIPLIER_DIRECT);
                        metricsConfig.addGpu(new AxKernelMetricsReader.GpuConfig(
                                curFreq.isFile() ? curFreq.getAbsolutePath() : null,
                                minFreq.isFile() ? minFreq.getAbsolutePath() : null,
                                maxFreq.isFile() ? maxFreq.getAbsolutePath() : null,
                                load.isFile() ? load.getAbsolutePath() : null,
                                MULTIPLIER_DIRECT));
                        break;
                    }
                }
            }
        }
    }

    private static int[] readCpuIds(File policyDir, int fallbackId) {
        File affectedFile = new File(policyDir, NODE_AFFECTED_CPUS);
        if (!affectedFile.isFile()) affectedFile = new File(policyDir, NODE_RELATED_CPUS);
        if (affectedFile.isFile()) {
            String text = AxKernelUtils.readSysfsString(affectedFile.getAbsolutePath());
            int[] ids = AxKernelUtils.parseCpuIds(text);
            if (ids.length > 0) return ids;
        }
        return new int[] { fallbackId };
    }

    private static int[] indexedValues(int length) {
        int[] values = new int[length];
        for (int i = 0; i < length; i++) values[i] = i;
        return values;
    }

    private static int stringIndex(String[] items, String target, int def) {
        if (items != null && target != null) {
            for (int i = 0; i < items.length; i++) {
                if (items[i].equals(target)) return i;
            }
        }
        return def;
    }

    private static boolean canUse(String path) {
        return !TextUtils.isEmpty(path) && new File(path).exists();
    }
}
