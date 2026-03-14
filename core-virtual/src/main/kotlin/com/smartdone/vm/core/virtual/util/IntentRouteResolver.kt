package com.smartdone.vm.core.virtual.util

import android.content.Intent

object IntentRouteResolver {
    private const val QUERY_VM_PACKAGE = "vm_pkg"
    private const val QUERY_PACKAGE = "pkg"

    fun resolvePackageName(intent: Intent): String? =
        resolvePackageName(
            componentPackage = intent.component?.packageName,
            intentPackage = intent.`package`,
            extraPackage = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME),
            dataString = intent.dataString
        )

    fun resolvePackageName(
        componentPackage: String? = null,
        intentPackage: String? = null,
        extraPackage: String? = null,
        dataString: String? = null
    ): String? =
        componentPackage
            ?: intentPackage
            ?: extraPackage
            ?: queryParameter(dataString, QUERY_VM_PACKAGE)
            ?: queryParameter(dataString, QUERY_PACKAGE)

    private fun queryParameter(dataString: String?, key: String): String? {
        val query = dataString?.substringAfter('?', "")?.takeIf { it.isNotEmpty() } ?: return null
        return query.split('&')
            .firstNotNullOfOrNull { segment ->
                val parts = segment.split('=', limit = 2)
                if (parts.firstOrNull() == key) parts.getOrNull(1) else null
            }
    }
}
