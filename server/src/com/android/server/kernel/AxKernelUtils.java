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

import android.os.FileUtils;
import android.text.TextUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.TreeSet;

final class AxKernelUtils {
    private static final String REGEX_WHITESPACE_COMMA = "[\\s,]+";
    private static final String DELIMITER_COMMA_SPACE = ", ";
    private static final String EMPTY_STRING = "";
    private static final String FALLBACK_CPU_RANGE = "0";

    private static final String UNIT_GHZ_FORMAT = "%.2f GHz";
    private static final String UNIT_MHZ_FORMAT = "%d MHz";

    private static final char CHAR_DASH = '-';
    private static final char CHAR_ZERO = '0';
    private static final char CHAR_NINE = '9';

    private static final int BUFFER_SIZE_TEXT = 2048;
    private static final int BUFFER_SIZE_VALUE = 64;
    private static final int KHZ_PER_GHZ = 1_000_000;
    private static final int KHZ_PER_MHZ = 1000;
    private static final float KHZ_PER_GHZ_FLOAT = 1_000_000.0f;
    private static final int HZ_THRESHOLD = 10_000_000;
    private static final int KHZ_THRESHOLD = 10_000;

    private AxKernelUtils() {
    }

    static int[] parseCpuIds(String text) {
        if (TextUtils.isEmpty(text)) return new int[0];
        TreeSet<Integer> cpus = new TreeSet<>();
        for (String token : text.split(REGEX_WHITESPACE_COMMA)) {
            addParsedToken(token, cpus);
        }
        int[] result = new int[cpus.size()];
        int idx = 0;
        for (Integer cpu : cpus) result[idx++] = cpu;
        return result;
    }

    private static void addParsedToken(String token, TreeSet<Integer> outCpus) {
        if (token.isEmpty()) return;
        int dash = token.indexOf(CHAR_DASH);
        if (dash <= 0 || dash >= token.length() - 1) {
            int val = extractNumber(token);
            if (val >= 0) outCpus.add(val);
            return;
        }
        int start = extractNumber(token.substring(0, dash));
        int end = extractNumber(token.substring(dash + 1));
        if (start >= 0 && end >= start) {
            for (int i = start; i <= end; i++) outCpus.add(i);
        }
    }

    static String formatCpuRange(int[] cpuIds) {
        if (cpuIds == null || cpuIds.length == 0) return FALLBACK_CPU_RANGE;
        Arrays.sort(cpuIds);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < cpuIds.length) {
            int start = cpuIds[i];
            int end = start;
            while (i + 1 < cpuIds.length && cpuIds[i + 1] == end + 1) {
                end = cpuIds[i + 1];
                i++;
            }
            if (sb.length() > 0) sb.append(DELIMITER_COMMA_SPACE);
            if (start == end) {
                sb.append(start);
            } else if (end == start + 1) {
                sb.append(start).append(DELIMITER_COMMA_SPACE).append(end);
            } else {
                sb.append(start).append(CHAR_DASH).append(end);
            }
            i++;
        }
        return sb.toString();
    }

    static int extractNumber(String text) {
        if (text == null) return -1;
        int i = 0;
        int len = text.length();
        while (i < len && (text.charAt(i) < CHAR_ZERO || text.charAt(i) > CHAR_NINE)) i++;
        if (i >= len) return -1;
        int num = 0;
        while (i < len && text.charAt(i) >= CHAR_ZERO && text.charAt(i) <= CHAR_NINE) {
            num = num * 10 + (text.charAt(i) - CHAR_ZERO);
            i++;
        }
        return num;
    }

    static String readSysfsString(String path) {
        if (TextUtils.isEmpty(path)) return EMPTY_STRING;
        try {
            return FileUtils.readTextFile(new File(path), BUFFER_SIZE_TEXT, null).trim();
        } catch (IOException e) {
            return EMPTY_STRING;
        }
    }

    static int readSysfsInt(String path, int defaultValue) {
        String s = readSysfsString(path);
        if (s.isEmpty()) return defaultValue;
        int val = extractNumber(s);
        return val >= 0 ? val : defaultValue;
    }

    static long readSysfsLong(String path, long defaultValue) {
        if (TextUtils.isEmpty(path)) return defaultValue;
        try {
            String text = FileUtils.readTextFile(new File(path), BUFFER_SIZE_VALUE, null).trim();
            if (text.isEmpty()) return defaultValue;
            long val = Long.parseLong(text.split("\\s+")[0]);
            return val >= 0 ? val : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    static double readSysfsDouble(String path, double defaultValue) {
        if (TextUtils.isEmpty(path)) return defaultValue;
        try {
            String text = FileUtils.readTextFile(new File(path), BUFFER_SIZE_VALUE, null).trim();
            if (text.isEmpty()) return defaultValue;
            return Double.parseDouble(text.split("\\s+")[0]);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    static int[] readSysfsIntList(String path) {
        String text = readSysfsString(path);
        if (text.isEmpty()) return new int[0];
        TreeSet<Integer> set = new TreeSet<>();
        for (String token : text.split(REGEX_WHITESPACE_COMMA)) {
            if (token.isEmpty()) continue;
            int val = extractNumber(token);
            if (val >= 0) set.add(val);
        }
        int[] result = new int[set.size()];
        int idx = 0;
        for (Integer val : set) result[idx++] = val;
        return result;
    }

    static String[] readSysfsTokens(String path) {
        String text = readSysfsString(path);
        if (text.isEmpty()) return new String[0];
        return Arrays.stream(text.split(REGEX_WHITESPACE_COMMA)).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    static String formatFrequency(int frequencyKhz) {
        if (frequencyKhz >= KHZ_PER_GHZ) {
            return String.format(Locale.US, UNIT_GHZ_FORMAT, frequencyKhz / KHZ_PER_GHZ_FLOAT);
        }
        return String.format(Locale.US, UNIT_MHZ_FORMAT, frequencyKhz / KHZ_PER_MHZ);
    }

    static String[] formatFrequencyLabels(int[] frequenciesKhz) {
        if (frequenciesKhz == null || frequenciesKhz.length == 0) return new String[0];
        String[] labels = new String[frequenciesKhz.length];
        for (int i = 0; i < frequenciesKhz.length; i++) {
            labels[i] = formatFrequency(frequenciesKhz[i]);
        }
        return labels;
    }

    static int toKhz(int rawFreq, long multiplier) {
        if (rawFreq <= 0) return 0;
        if (rawFreq >= HZ_THRESHOLD) {
            return rawFreq / KHZ_PER_MHZ;
        }
        if (multiplier > 1L) {
            return (int) ((rawFreq * multiplier) / KHZ_PER_MHZ);
        }
        if (rawFreq >= KHZ_THRESHOLD) {
            return rawFreq;
        }
        return rawFreq * KHZ_PER_MHZ;
    }

    static int[] toKhzList(int[] rawFreqs, long multiplier) {
        if (rawFreqs == null || rawFreqs.length == 0) return new int[0];
        int[] result = new int[rawFreqs.length];
        for (int i = 0; i < rawFreqs.length; i++) {
            result[i] = toKhz(rawFreqs[i], multiplier);
        }
        return result;
    }

    static String[] toStringValues(int[] values) {
        if (values == null || values.length == 0) return new String[0];
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = Integer.toString(values[i]);
        }
        return result;
    }
}
