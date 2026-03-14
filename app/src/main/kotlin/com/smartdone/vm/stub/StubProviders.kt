package com.smartdone.vm.stub

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

open class BaseStubProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

class StubProvider_P0 : BaseStubProvider()
class StubProvider_P1 : BaseStubProvider()
class StubProvider_P2 : BaseStubProvider()
class StubProvider_P3 : BaseStubProvider()
class StubProvider_P4 : BaseStubProvider()
class StubProvider_P5 : BaseStubProvider()
class StubProvider_P6 : BaseStubProvider()
class StubProvider_P7 : BaseStubProvider()
class StubProvider_P8 : BaseStubProvider()
class StubProvider_P9 : BaseStubProvider()
