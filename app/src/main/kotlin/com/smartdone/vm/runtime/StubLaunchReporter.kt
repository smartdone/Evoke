package com.smartdone.vm.runtime

import android.content.Context

data class StubLaunchReport(
    val packageName: String,
    val userId: Int,
    val realActivity: String,
    val applicationStatus: String,
    val launcherStatus: String,
    val activityStatus: String,
    val failureStage: String,
    val failureMessage: String
)

object StubLaunchReporter {
    fun report(
        context: Context,
        packageName: String,
        userId: Int,
        realActivity: String,
        applicationStatus: String,
        launcherStatus: String,
        activityStatus: String,
        failureStage: String = "",
        failureMessage: String = ""
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE_NAME, packageName)
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_REAL_ACTIVITY, realActivity)
            .putString(KEY_APPLICATION_STATUS, applicationStatus)
            .putString(KEY_LAUNCHER_STATUS, launcherStatus)
            .putString(KEY_ACTIVITY_STATUS, activityStatus)
            .putString(KEY_FAILURE_STAGE, failureStage)
            .putString(KEY_FAILURE_MESSAGE, failureMessage)
            .commit()
    }

    fun lastReport(context: Context): StubLaunchReport? {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val packageName = preferences.getString(KEY_PACKAGE_NAME, null) ?: return null
        return StubLaunchReport(
            packageName = packageName,
            userId = preferences.getInt(KEY_USER_ID, 0),
            realActivity = preferences.getString(KEY_REAL_ACTIVITY, "").orEmpty(),
            applicationStatus = preferences.getString(KEY_APPLICATION_STATUS, "").orEmpty(),
            launcherStatus = preferences.getString(KEY_LAUNCHER_STATUS, "").orEmpty(),
            activityStatus = preferences.getString(KEY_ACTIVITY_STATUS, "").orEmpty(),
            failureStage = preferences.getString(KEY_FAILURE_STAGE, "").orEmpty(),
            failureMessage = preferences.getString(KEY_FAILURE_MESSAGE, "").orEmpty()
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private const val PREFS_NAME = "stub-launch-reporter"
    private const val KEY_PACKAGE_NAME = "packageName"
    private const val KEY_USER_ID = "userId"
    private const val KEY_REAL_ACTIVITY = "realActivity"
    private const val KEY_APPLICATION_STATUS = "applicationStatus"
    private const val KEY_LAUNCHER_STATUS = "launcherStatus"
    private const val KEY_ACTIVITY_STATUS = "activityStatus"
    private const val KEY_FAILURE_STAGE = "failureStage"
    private const val KEY_FAILURE_MESSAGE = "failureMessage"
}
