package com.smartdone.vm.core.virtual.server

import android.net.Uri

data class ProviderRoute(
    val packageName: String,
    val originalAuthority: String,
    val stubAuthority: String,
    val slotId: Int
)

object AuthorityMapper {
    private const val PARAM_ORIGINAL_AUTHORITY = "__vm_orig_auth"
    private const val PARAM_PACKAGE_NAME = "__vm_pkg"
    private const val PARAM_USER_ID = "__vm_user"

    fun createRoute(
        hostPackage: String,
        packageName: String,
        originalAuthority: String,
        slotId: Int
    ): ProviderRoute = ProviderRoute(
        packageName = packageName,
        originalAuthority = originalAuthority,
        stubAuthority = "$hostPackage.vcp.p$slotId",
        slotId = slotId
    )

    fun toHostUri(uri: Uri, route: ProviderRoute, userId: Int): Uri =
        uri.buildUpon()
            .authority(route.stubAuthority)
            .appendQueryParameter(PARAM_ORIGINAL_AUTHORITY, route.originalAuthority)
            .appendQueryParameter(PARAM_PACKAGE_NAME, route.packageName)
            .appendQueryParameter(PARAM_USER_ID, userId.toString())
            .build()

    fun appendIsolationMetadata(
        originalUri: String,
        route: ProviderRoute,
        userId: Int
    ): String {
        val separator = if ('?' in originalUri) '&' else '?'
        return buildString {
            append(originalUri)
            append(separator)
            append(PARAM_ORIGINAL_AUTHORITY)
            append('=')
            append(route.originalAuthority)
            append('&')
            append(PARAM_PACKAGE_NAME)
            append('=')
            append(route.packageName)
            append('&')
            append(PARAM_USER_ID)
            append('=')
            append(userId)
        }
    }

    fun originalAuthority(uri: Uri): String? =
        uri.getQueryParameter(PARAM_ORIGINAL_AUTHORITY)

    fun packageName(uri: Uri): String? =
        uri.getQueryParameter(PARAM_PACKAGE_NAME)

    fun userId(uri: Uri): Int =
        uri.getQueryParameter(PARAM_USER_ID)?.toIntOrNull() ?: 0

    fun originalAuthority(uri: String): String? =
        queryParameter(uri, PARAM_ORIGINAL_AUTHORITY)

    fun packageName(uri: String): String? =
        queryParameter(uri, PARAM_PACKAGE_NAME)

    fun userId(uri: String): Int =
        queryParameter(uri, PARAM_USER_ID)?.toIntOrNull() ?: 0

    private fun queryParameter(uri: String, key: String): String? {
        val query = uri.substringAfter('?', "")
        if (query.isEmpty()) return null
        return query.split('&')
            .firstNotNullOfOrNull { segment ->
                val parts = segment.split('=', limit = 2)
                if (parts.firstOrNull() == key) parts.getOrNull(1) else null
            }
    }
}
