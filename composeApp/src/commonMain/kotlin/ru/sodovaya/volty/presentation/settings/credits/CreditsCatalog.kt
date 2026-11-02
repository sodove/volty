package ru.sodovaya.volty.presentation.settings.credits

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class CreditProject(
    val id: String,
    val name: String,
    val version: String? = null,
    val authors: List<String> = emptyList(),
    val website: String? = null,
    val licenseIds: List<String>,
    val notice: String? = null,
)

@Serializable
data class CreditLicense(
    val id: String,
    val name: String,
    val content: String,
)

@Serializable
data class CreditsCatalog(
    val projects: List<CreditProject>,
    val licenses: Map<String, CreditLicense>,
) {
    init {
        require(projects.all { it.id.isNotBlank() }) { "Every credits project must have a nonblank id" }
        require(projects.map { it.id }.toSet().size == projects.size) { "Credits project ids must be unique" }
        require(projects.all { it.name.isNotBlank() }) { "Every credits project must have a nonblank name" }
        require(projects.all { it.licenseIds.isNotEmpty() }) { "Every credits project must reference at least one license" }
        require(projects.all { project -> project.licenseIds.all(licenses::containsKey) }) {
            "Every credits project license id must exist in the license catalog"
        }
        require(licenses.all { (key, license) -> key == license.id }) {
            "Every license map key must match the license id"
        }
        require(licenses.all { (key, license) -> key.isNotBlank() && license.name.isNotBlank() }) {
            "Every license must have a nonblank id and name"
        }
        require(licenses.all { (_, license) -> license.content.isNotBlank() }) {
            "Every license must contain its full text"
        }
    }

    companion object {
        /** Decodes the offline catalog and rejects references or texts that cannot be displayed. */
        fun parse(json: String): CreditsCatalog = jsonFormat.decodeFromString(json)

        private val jsonFormat = Json { ignoreUnknownKeys = true }
    }
}
