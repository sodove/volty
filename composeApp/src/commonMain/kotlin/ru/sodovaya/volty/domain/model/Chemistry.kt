package ru.sodovaya.volty.domain.model

enum class Chemistry(
    val label: String,
    val nominalCellV: Float,
    val defaultHighV: Float,
    val defaultLowV: Float,
    /**
     * The cell voltage at which a VEHICLE has no usable energy left — the
     * floor of the usable range, used only as the 0 % point of the
     * voltage-based SoC estimate (see VoltageSocEstimator).
     *
     * This is deliberately NOT [defaultLowV]. That one is the cell-damage
     * threshold and drives the low-cell alert; it sits lower on purpose. An
     * EUC stops delivering power well above the damage floor — riders treat
     * roughly 3.3 V/cell (Li-ion) as practically empty — so the SoC estimate
     * reaches 0 % first and the low-cell alert fires later: "you are out of
     * range" before "the cells are being damaged".
     */
    val emptyCellV: Float
) {
    LI_ION_NMC("Li-ion (NMC)", nominalCellV = 3.7f, defaultHighV = 4.20f, defaultLowV = 2.80f, emptyCellV = 3.30f),
    LIFEPO4("LiFePO4", nominalCellV = 3.2f, defaultHighV = 3.65f, defaultLowV = 2.50f, emptyCellV = 3.00f),
    LEAD_ACID("Lead-acid", nominalCellV = 2.0f, defaultHighV = 2.45f, defaultLowV = 1.75f, emptyCellV = 1.95f)
}
