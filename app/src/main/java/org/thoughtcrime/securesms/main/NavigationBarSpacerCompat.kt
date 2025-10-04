/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.signal.core.util.DimensionUnit
import org.thoughtcrime.securesms.util.ViewUtil

@Composable
fun NavigationBarSpacerCompat() {
  if (Build.VERSION.SDK_INT >= 23) {
    Spacer(Modifier.navigationBarsPadding())
  } else {
    val resources = LocalContext.current.resources
    val navigationBarHeight = remember(resources) {
      DimensionUnit.PIXELS.toDp(ViewUtil.getNavigationBarHeight(resources).toFloat()).dp
    }

    Spacer(Modifier.height(navigationBarHeight))
  }
}
