package ru.sodovaya.volty.data.nearby

import io.ktor.http.URLProtocol
import io.ktor.http.Url

internal fun nearbyHttpsApiBaseUrl(baseUrl: String): String {
    require(baseUrl.isNotBlank())
    require('?' !in baseUrl && '#' !in baseUrl)
    val schemeSeparator = baseUrl.indexOf("://")
    require(schemeSeparator > 0)
    val rawAuthority = baseUrl.substring(schemeSeparator + 3)
        .substringBefore('/')
        .substringBefore('\\')
    require(rawAuthority.isNotBlank())
    require('@' !in rawAuthority)
    val rawHost = if (rawAuthority.startsWith('[')) {
        val closingBracket = rawAuthority.indexOf(']')
        require(closingBracket > 1)
        rawAuthority.substring(1, closingBracket)
    } else {
        rawAuthority.substringBefore(':')
    }
    require(rawHost.isNotBlank())
    val root = baseUrl.trimEnd('/')
    require(root.isNotBlank())
    val url = Url(root)
    require(
        url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() &&
            url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
            url.fragment.isEmpty() && url.parameters.isEmpty(),
    )
    return root
}
