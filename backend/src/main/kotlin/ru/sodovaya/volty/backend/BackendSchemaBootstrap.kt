package ru.sodovaya.volty.backend

import ru.sodovaya.volty.backend.nearby.JdbcLegacyImportRetirement
import javax.sql.DataSource

/** Complete before constructing HTTP dependencies; failure prevents startup. */
internal fun prepareBackendSchema(source: DataSource, mode: ru.sodovaya.volty.backend.nearby.NearbyCompatibility = ru.sodovaya.volty.backend.nearby.NearbyCompatibility.LEGACY) {
    SchemaMigrator(source).migrate()
    JdbcLegacyImportRetirement(source).retire(::nowMillis)
    if (mode == ru.sodovaya.volty.backend.nearby.NearbyCompatibility.VOICE_SPACES) {
        ru.sodovaya.volty.backend.nearby.JdbcLegacySharingRetirement(source).retire(::nowMillis)
    }
}
