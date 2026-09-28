package com.dwk.yumgo

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

/** Presets and theme. A plain serializable key, so it is part of the saved back stack. */
@Serializable data object Settings : NavKey
