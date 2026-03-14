package com.smartdone.vm.permission

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.smartdone.vm.core.virtual.permission.PermissionRequestContract
import com.smartdone.vm.core.virtual.server.PermissionDelegateService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class PermissionRequestActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val packageName = intent.getStringExtra(PermissionRequestContract.EXTRA_PACKAGE_NAME).orEmpty()
        val permissionName = intent.getStringExtra(PermissionRequestContract.EXTRA_PERMISSION_NAME).orEmpty()
        if (packageName.isNotBlank() && permissionName.isNotBlank()) {
            entryPoint().permissionDelegateService().updatePermission(packageName, permissionName, granted)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissionName = intent.getStringExtra(PermissionRequestContract.EXTRA_PERMISSION_NAME)
        if (permissionName.isNullOrBlank()) {
            finish()
            return
        }
        permissionLauncher.launch(permissionName)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PermissionRequestEntryPoint {
        fun permissionDelegateService(): PermissionDelegateService
    }

    private fun entryPoint(): PermissionRequestEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, PermissionRequestEntryPoint::class.java)
}
