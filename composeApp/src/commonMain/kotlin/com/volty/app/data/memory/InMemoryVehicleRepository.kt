package com.volty.app.data.memory

import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class InMemoryVehicleRepository : VehicleRepository {

    private val state = MutableStateFlow<List<Vehicle>>(emptyList())
    override val vehicles: StateFlow<List<Vehicle>> = state.asStateFlow()

    override suspend fun get(id: String): Vehicle? = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(vehicle: Vehicle) {
        state.update { list ->
            val without = list.filterNot { it.id == vehicle.id }
            (without + vehicle).sortedByDescending { it.lastConnectedAt ?: it.createdAt }
        }
    }

    override suspend fun delete(id: String) {
        state.update { it.filterNot { v -> v.id == id } }
    }

    override suspend fun touch(id: String) {
        state.update { list ->
            list.map { v -> if (v.id == id) v.copy(lastConnectedAt = Clock.System.now()) else v }
        }
    }
}
