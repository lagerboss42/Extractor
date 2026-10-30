package com.majk.extractor.data.model

/**
 * Recurring stops that are not in the Opter manifest.
 * Toggled on-demand in the UI; active ones are appended to Excel with Info = "kkböj".
 */
data class FixedStop(
    val id: String,
    val section: String,
    val recipient: String,
    val address: String,
    val zip: String,
    val city: String
) {
    fun toShipment(): Shipment = Shipment(
        type = ShipmentType.DEL,
        recipient = recipient,
        address = address,
        zip = zip,
        city = city,
        colliCount = 0,
        weightKg = 0,
        portkod = null,
        comment = null,
        carrier = "kkböj"
    )
}

object FixedStops {
    val all: List<FixedStop> = listOf(
        FixedStop("anicura", "Malmslätt", "AniCura Jägarvallen", "Jägarvallsvägen 2", "58422", "Linköping"),
        FixedStop("karna_vardby", "Malmslätt", "Kärna Vårdby", "Kärnavägen 1", "58662", "Linköping"),
        FixedStop("vardbostader", "Malmslätt", "Vårdbostäder", "Kärna Centrum 1", "58665", "Linköping"),
        FixedStop("apotek_humlan", "Malmslätt", "Apoteket Humlan", "Kärnabrunnsgatan 10", "58665", "Linköping"),
        FixedStop("ekhult", "Ljungsbro", "Ekhult", "Ljung Ekhult 1B", "58575", "Ljungsbro"),
        FixedStop("kronans", "Ljungsbro", "Kronans Apotek Ljungsbro", "Evastigen 9", "58571", "Ljungsbro"),
        FixedStop("vretaliden", "Ljungsbro", "Vretaliden", "Evastigen 19", "58571", "Ljungsbro"),
        FixedStop("harnebacken", "Ljungsbro", "Härnebacken", "Härnegatan 75", "58574", "Ljungsbro"),
        FixedStop("kanalgarden", "Vreta Kloster", "Kanalgården", "Trossvägen 3", "58577", "Vreta Kloster"),
        FixedStop("anstalten", "Skänninge", "Anstalten Skänninge", "93XV+5HF Skänninge", "59621", "Skänninge"),
        FixedStop("anggarden", "Skänninge", "Änggården", "Villagatan 1", "59633", "Skänninge"),
        FixedStop("apotek_skanninge", "Skänninge", "Apoteket Skänninge", "Östra Kyrkogatan 5", "59634", "Skänninge"),
        FixedStop("eldslukargrand", "Skänninge", "Eldslukargränd", "Eldslukargränd 7", "59634", "Skänninge")
    )

    val sections: List<String> = listOf("Malmslätt", "Ljungsbro", "Vreta Kloster", "Skänninge")
}
