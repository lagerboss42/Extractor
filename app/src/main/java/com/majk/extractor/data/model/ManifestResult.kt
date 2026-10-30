package com.majk.extractor.data.model

/** Result of a full capture → clean → geocode pipeline. */
data class ManifestResult(
    val shipments: List<Shipment>,
    val sourceLabel: String = "capture"
)
