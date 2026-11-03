package com.majk.extractor.util

import kotlin.math.ceil

object WeightUtils {
    /** Always round up to nearest whole kg (0.1 → 1, 3.7 → 4). */
    fun roundToWhole(weight: Number): Int {
        val v = weight.toDouble()
        if (v <= 0.0) return 0
        return ceil(v - 1e-9).toInt()
    }
}
