/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.settings.fragments.miscellaneous

import android.content.Context
import android.os.UserHandle
import com.android.settings.core.BasePreferenceController

class KernelManagerPreferenceController(context: Context, key: String) :
        BasePreferenceController(context, key) {
    override fun getAvailabilityStatus(): Int =
            if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) AVAILABLE else DISABLED_FOR_USER
}
