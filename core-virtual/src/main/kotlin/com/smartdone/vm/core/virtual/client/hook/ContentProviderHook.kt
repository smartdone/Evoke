package com.smartdone.vm.core.virtual.client.hook

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.smartdone.vm.core.virtual.server.AuthorityMapper
import com.smartdone.vm.core.virtual.server.EvokeServiceFetcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentProviderHook @Inject constructor(
    private val serviceFetcher: EvokeServiceFetcher
) {
    private var installed = false
    private var appContext: Context? = null
    private var defaultUserId: Int = 0
    private val authorityMap = mutableMapOf<String, String>()

    fun install(context: Context, userId: Int) {
        installed = true
        appContext = context.applicationContext
        defaultUserId = userId
        Log.d("ContentProviderHook", "Content provider hook installed")
    }

    fun rewriteAuthority(authority: String): String {
        return authorityMap.getOrPut(authority) {
            serviceFetcher.contentProviderManager()
                ?.getProviderInfo(authority)
                ?.getString("hostAuthority")
                ?: authority
        }
    }

    fun acquireProvider(authority: String): Bundle? =
        serviceFetcher.contentProviderManager()?.acquireProvider(authority)

    fun rewriteUri(uri: Uri, userId: Int): Uri {
        val authority = uri.authority ?: return uri
        val providerInfo = serviceFetcher.contentProviderManager()?.getProviderInfo(authority) ?: return uri
        val hostAuthority = providerInfo.getString("hostAuthority") ?: return uri
        val packageName = providerInfo.getString("packageName").orEmpty()
        val slotId = providerInfo.getInt("slotId", -1)
        if (slotId < 0 || packageName.isBlank()) return uri
        return AuthorityMapper.toHostUri(
            uri = uri,
            route = com.smartdone.vm.core.virtual.server.ProviderRoute(
                packageName = packageName,
                originalAuthority = authority,
                stubAuthority = hostAuthority,
                slotId = slotId
            ),
            userId = userId
        )
    }

    fun query(
        uri: Uri,
        projection: Array<String>? = null,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null,
        userId: Int = defaultUserId
    ): Cursor? =
        appContext?.contentResolver?.query(
            rewriteUri(uri, userId),
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

    fun insert(uri: Uri, values: ContentValues, userId: Int = defaultUserId): Uri? =
        appContext?.contentResolver?.insert(rewriteUri(uri, userId), values)

    fun update(
        uri: Uri,
        values: ContentValues,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        userId: Int = defaultUserId
    ): Int =
        appContext?.contentResolver?.update(
            rewriteUri(uri, userId),
            values,
            selection,
            selectionArgs
        ) ?: 0

    fun delete(
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        userId: Int = defaultUserId
    ): Int =
        appContext?.contentResolver?.delete(
            rewriteUri(uri, userId),
            selection,
            selectionArgs
        ) ?: 0

    fun call(
        authority: String,
        method: String,
        arg: String? = null,
        extras: Bundle? = null
    ): Bundle? =
        appContext?.contentResolver?.call(
            acquireProvider(authority)?.getString("hostAuthority") ?: rewriteAuthority(authority),
            method,
            arg,
            extras
        )
}
