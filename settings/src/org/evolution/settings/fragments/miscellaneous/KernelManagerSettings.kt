/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.settings.fragments.miscellaneous

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.kernel.AxKernelControl
import com.android.internal.kernel.AxKernelManager
import com.android.internal.kernel.AxKernelMetrics
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class KernelManagerSettings : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
            savedInstanceState: Bundle?): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme { KernelManagerScreen(viewLifecycleOwner.lifecycle) }
        }
        activity?.title = getString(R.string.kernel_manager_title)
    }
}

private data class KernelSnapshot(val controls: List<AxKernelControl>,
        val metrics: AxKernelMetrics?, val applyOnBoot: Boolean)

private suspend fun readSnapshot(previous: AxKernelMetrics?): KernelSnapshot =
    withContext(Dispatchers.IO) {
        KernelSnapshot(AxKernelManager.getControls(), AxKernelManager.getMetrics(
                previous?.totalCpuActiveTimeTicks ?: AxKernelMetrics.CPU_TIME_UNAVAILABLE_TICKS,
                previous?.totalCpuTimeTicks ?: AxKernelMetrics.CPU_TIME_UNAVAILABLE_TICKS),
                AxKernelManager.getApplyOnBoot())
    }

@Composable
private fun KernelManagerScreen(lifecycle: Lifecycle) {
    var snapshot by remember { mutableStateOf<KernelSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var selectedControl by remember { mutableStateOf<AxKernelControl?>(null) }
    var resetDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun runAction(action: () -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) { action() }
                snapshot = readSnapshot(snapshot?.metrics)
                error = !success
            } catch (e: CancellationException) { throw e }
            catch (e: RuntimeException) { error = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (!busy) {
                    try { snapshot = readSnapshot(snapshot?.metrics) }
                    catch (e: CancellationException) { throw e }
                    catch (e: RuntimeException) { error = true }
                }
                delay(2000)
            }
        }
    }
    val data = snapshot
    val metrics = data?.metrics
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.kernel_manager_description),
                style = MaterialTheme.typography.bodyMedium)
        if (error) Text(stringResource(R.string.kernel_manager_apply_failed),
                color = MaterialTheme.colorScheme.error)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.kernel_manager_live_metrics),
                        style = MaterialTheme.typography.titleMedium)
                MetricRow(stringResource(R.string.kernel_manager_cpu_load),
                        percent(metrics?.cpuUsagePercent))
                metrics?.cpuClusters?.forEach { cpu ->
                    MetricRow(cpu.group, frequency(cpu.currentFrequencyHz))
                }
                metrics?.gpu?.let { gpu ->
                    MetricRow(stringResource(R.string.kernel_manager_gpu_frequency),
                            frequency(gpu.currentFrequencyHz))
                    MetricRow(stringResource(R.string.kernel_manager_gpu_load),
                            percent(gpu.usagePercent))
                }
            }
        }
        if (data == null) {
            Text(stringResource(R.string.kernel_manager_loading))
        } else if (data.controls.isEmpty()) {
            Text(stringResource(R.string.kernel_manager_unavailable))
        } else {
            data.controls.groupBy { it.group }.forEach { (group, controls) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(group, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleMedium)
                        controls.sortedBy { control ->
                            when (control.type) {
                                AxKernelControl.TYPE_CPU_MIN_FREQ,
                                AxKernelControl.TYPE_GPU_MIN_FREQ -> 0
                                AxKernelControl.TYPE_CPU_MAX_FREQ,
                                AxKernelControl.TYPE_GPU_MAX_FREQ -> 1
                                AxKernelControl.TYPE_CPU_GOVERNOR -> 2
                                else -> 3
                            }
                        }.forEach { control ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                selectedControl = control
                            }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(controlTitle(control.type), Modifier.weight(1f))
                                Text(controlLabel(control, control.currentValue),
                                        color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.kernel_manager_apply_on_boot))
                        Text(stringResource(R.string.kernel_manager_apply_on_boot_summary),
                                style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = data?.applyOnBoot == true,
                            enabled = !busy && data != null && data.controls.isNotEmpty(),
                            onCheckedChange = { enabled ->
                                runAction { AxKernelManager.setApplyOnBoot(enabled) }
                            })
                }
                TextButton(enabled = !busy && data != null, onClick = { resetDialog = true }) {
                    Text(stringResource(R.string.kernel_manager_reset))
                }
            }
        }
    }
    selectedControl?.let { control ->
        ControlDialog(control, onDismiss = { selectedControl = null }, onApply = { value ->
            selectedControl = null
            runAction { AxKernelManager.setControlValue(control.id, value) }
        })
    }
    if (resetDialog) AlertDialog(onDismissRequest = { resetDialog = false },
            title = { Text(stringResource(R.string.kernel_manager_reset)) },
            text = { Text(stringResource(R.string.kernel_manager_reset_summary)) },
            confirmButton = {
                TextButton(onClick = {
                    resetDialog = false
                    runAction { AxKernelManager.resetControls() }
                }) { Text(stringResource(R.string.kernel_manager_reset)) }
            }, dismissButton = {
                TextButton(onClick = { resetDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            })
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Text(value)
    }
}

@Composable
private fun ControlDialog(control: AxKernelControl, onDismiss: () -> Unit,
        onApply: (Int) -> Unit) {
    var selected by remember(control.id) { mutableStateOf(control.currentValue) }
    AlertDialog(onDismissRequest = onDismiss,
            title = { Text(controlTitle(control.type)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    control.availableValues.forEach { value ->
                        Row(Modifier.fillMaxWidth().clickable { selected = value }.padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == value, onClick = { selected = value })
                            Text(controlLabel(control, value))
                        }
                    }
                }
            }, confirmButton = {
                TextButton(enabled = control.availableValues.contains(selected),
                        onClick = { onApply(selected) }) {
                    Text(stringResource(R.string.kernel_manager_apply))
                }
            }, dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            })
}

@Composable
private fun controlTitle(type: Int): String = stringResource(when (type) {
    AxKernelControl.TYPE_CPU_MIN_FREQ, AxKernelControl.TYPE_GPU_MIN_FREQ ->
        R.string.kernel_manager_min_frequency
    AxKernelControl.TYPE_CPU_MAX_FREQ, AxKernelControl.TYPE_GPU_MAX_FREQ ->
        R.string.kernel_manager_max_frequency
    else -> R.string.kernel_manager_governor
})

@Composable
private fun controlLabel(control: AxKernelControl, value: Int): String {
    val index = control.availableValues.indexOf(value)
    val labels = control.valueLabels
    if (index in labels.indices) return labels[index]
    if (value == Integer.MIN_VALUE) return "—"
    if (value == 0 && control.type != AxKernelControl.TYPE_CPU_GOVERNOR)
        return stringResource(R.string.kernel_manager_no_limit)
    return if (control.type == AxKernelControl.TYPE_CPU_GOVERNOR) "—"
            else frequency(value.toLong() * 1000L)
}

private fun frequency(hz: Long): String = if (hz <= 0L) "—"
        else String.format(Locale.getDefault(), "%.0f MHz", hz / 1_000_000.0)
private fun percent(value: Float?): String = if (value == null || value < 0f) "—"
        else String.format(Locale.getDefault(), "%.1f %%", value)
