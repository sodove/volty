package com.volty.app.domain.model

enum class Chemistry(
    val label: String,
    val nominalCellV: Float,
    val defaultHighV: Float,
    val defaultLowV: Float
) {
    LI_ION_NMC("Li-ion (NMC)", nominalCellV = 3.7f, defaultHighV = 4.20f, defaultLowV = 2.80f),
    LIFEPO4("LiFePO4", nominalCellV = 3.2f, defaultHighV = 3.65f, defaultLowV = 2.50f),
    LEAD_ACID("Lead-acid", nominalCellV = 2.0f, defaultHighV = 2.45f, defaultLowV = 1.75f)
}
