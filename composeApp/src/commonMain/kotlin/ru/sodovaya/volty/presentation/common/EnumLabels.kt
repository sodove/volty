package ru.sodovaya.volty.presentation.common

import androidx.compose.runtime.Composable
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.bms_ant
import volty.composeapp.generated.resources.bms_daly
import volty.composeapp.generated.resources.bms_jbd
import volty.composeapp.generated.resources.bms_jk
import volty.composeapp.generated.resources.chemistry_lead_acid
import volty.composeapp.generated.resources.chemistry_lifepo4
import volty.composeapp.generated.resources.chemistry_li_ion_nmc

@Composable
fun bmsTypeLabel(type: BmsType): String = stringResource(
    when (type) {
        BmsType.JK_BMS -> Res.string.bms_jk
        BmsType.JBD_BMS -> Res.string.bms_jbd
        BmsType.ANT_BMS -> Res.string.bms_ant
        BmsType.DALY_BMS -> Res.string.bms_daly
    }
)

@Composable
fun chemistryLabel(chemistry: Chemistry): String = stringResource(
    when (chemistry) {
        Chemistry.LI_ION_NMC -> Res.string.chemistry_li_ion_nmc
        Chemistry.LIFEPO4 -> Res.string.chemistry_lifepo4
        Chemistry.LEAD_ACID -> Res.string.chemistry_lead_acid
    }
)
