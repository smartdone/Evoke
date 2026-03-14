package com.smartdone.vm.core.virtual.util

import android.content.ComponentName
import android.content.Intent
import com.smartdone.vm.core.virtual.EvokeCore

data class StubActivityRecord(
    val stubClassName: String,
    val packageName: String,
    val userId: Int,
    val realIntent: Intent,
    val apkPath: String,
    val launcherActivity: String?,
    val applicationClassName: String?
)

object StubActivityRouter {
    private const val EXTRA_REAL_INTENT = "extra_real_intent"
    private const val EXTRA_APK_PATH = "extra_apk_path"
    private const val EXTRA_LAUNCHER_ACTIVITY = "extra_launcher_activity"
    private const val EXTRA_APPLICATION_CLASS = "extra_application_class"

    fun buildLaunchIntent(
        hostPackage: String,
        stubClassName: String,
        record: StubActivityRecord,
        label: String
    ): Intent = Intent().apply {
        component = ComponentName(hostPackage, stubClassName)
        putExtra(EvokeCore.EXTRA_PACKAGE_NAME, record.packageName)
        putExtra(EvokeCore.EXTRA_USER_ID, record.userId)
        putExtra(EvokeCore.EXTRA_LABEL, label)
        putExtra(EXTRA_REAL_INTENT, record.realIntent)
        putExtra(EXTRA_APK_PATH, record.apkPath)
        putExtra(EXTRA_LAUNCHER_ACTIVITY, record.launcherActivity)
        putExtra(EXTRA_APPLICATION_CLASS, record.applicationClassName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun read(intent: Intent, stubClassName: String): StubActivityRecord {
        val packageName = intent.getStringExtra(EvokeCore.EXTRA_PACKAGE_NAME).orEmpty()
        val userId = intent.getIntExtra(EvokeCore.EXTRA_USER_ID, 0)
        @Suppress("DEPRECATION")
        val realIntent = intent.getParcelableExtra(EXTRA_REAL_INTENT) as? Intent
            ?: Intent().apply {
                setClassName(packageName, intent.getStringExtra(EXTRA_LAUNCHER_ACTIVITY).orEmpty())
            }
        return StubActivityRecord(
            stubClassName = stubClassName,
            packageName = packageName,
            userId = userId,
            realIntent = realIntent,
            apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty(),
            launcherActivity = intent.getStringExtra(EXTRA_LAUNCHER_ACTIVITY),
            applicationClassName = intent.getStringExtra(EXTRA_APPLICATION_CLASS)
        )
    }
}
