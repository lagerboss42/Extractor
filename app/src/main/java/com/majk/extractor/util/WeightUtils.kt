package com.majk.extractor.util

import kotlin.math.roundToInt

object WeightUtils {
    fun roundToWhole(weight: Number): Int = weight.toDouble().roundToInt()
}
