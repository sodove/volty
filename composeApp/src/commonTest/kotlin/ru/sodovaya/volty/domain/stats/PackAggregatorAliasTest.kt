package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import kotlin.test.Test
import kotlin.test.assertEquals

class PackAggregatorAliasTest {
    private fun pack(i: Int, alias: String?) = Pack(i, "p$i", BmsType.ANT_BMS, "A$i", aliasGroup = alias)
    private fun state(i: Int, alias: String?, current: Float, online: Boolean = true) =
        PackState(pack(i, alias), BmsData(voltage = 50f, current = current, isConnected = true), isOnline = online)

    @Test fun two_online_alias_paths_count_once_lowest_index_wins() {
        // Same physical battery via two paths, both online → count only pack 0.
        val agg = PackAggregator.aggregate(
            listOf(state(0, "batt", current = 10f), state(1, "batt", current = 10f)),
            PackTopology.PARALLEL
        )
        assertEquals(10f, agg.current) // not 20f — collapsed
    }

    @Test fun alias_failover_keeps_battery_when_primary_offline() {
        val agg = PackAggregator.aggregate(
            listOf(state(0, "batt", current = 0f, online = false), state(1, "batt", current = 12f)),
            PackTopology.PARALLEL
        )
        assertEquals(12f, agg.current)
    }

    @Test fun null_aliasGroup_packs_stay_independent() {
        val agg = PackAggregator.aggregate(
            listOf(state(0, null, current = 10f), state(1, null, current = 12f)),
            PackTopology.PARALLEL
        )
        assertEquals(22f, agg.current)
    }
}
