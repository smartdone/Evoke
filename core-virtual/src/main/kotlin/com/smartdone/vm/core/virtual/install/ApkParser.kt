package com.smartdone.vm.core.virtual.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import com.smartdone.vm.core.virtual.model.ActivityComponentInfo
import com.smartdone.vm.core.virtual.model.ApkMetadata
import com.smartdone.vm.core.virtual.model.IntentDataSpec
import com.smartdone.vm.core.virtual.model.IntentFilterSpec
import com.smartdone.vm.core.virtual.model.ProviderComponentInfo
import com.smartdone.vm.core.virtual.model.ReceiverComponentInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.xmlpull.v1.XmlPullParser

@Singleton
class ApkParser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun parseArchive(apkPath: String): ApkMetadata? {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(
            PackageManager.GET_ACTIVITIES.toLong() or
                PackageManager.GET_SERVICES.toLong() or
                PackageManager.GET_PROVIDERS.toLong() or
                PackageManager.GET_RECEIVERS.toLong() or
                PackageManager.GET_PERMISSIONS.toLong()
        )
        val packageInfo = pm.getPackageArchiveInfo(apkPath, flags) ?: return null
        val appInfo = packageInfo.applicationInfo ?: return null
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        val manifestInfo = parseManifestInfo(apkPath, packageInfo.packageName.orEmpty())
        return ApkMetadata(
            packageName = packageInfo.packageName.orEmpty(),
            label = appInfo.loadLabel(pm)?.toString().orEmpty(),
            applicationClassName = appInfo.className,
            versionCode = packageInfo.longVersionCode,
            requestedPermissions = packageInfo.requestedPermissions?.toList().orEmpty(),
            activities = manifestInfo.activities.map(ActivityComponentInfo::className)
                .ifEmpty { packageInfo.activities?.mapNotNull { it.name }.orEmpty() },
            services = packageInfo.services?.mapNotNull { it.name }.orEmpty(),
            providers = manifestInfo.providers.map(ProviderComponentInfo::authority)
                .ifEmpty { packageInfo.providers?.mapNotNull { it.authority ?: it.name }.orEmpty() },
            receivers = manifestInfo.receivers.map(ReceiverComponentInfo::className)
                .ifEmpty { packageInfo.receivers?.mapNotNull { it.name }.orEmpty() },
            iconBitmap = appInfo.loadIcon(pm)?.toBitmapSafely(),
            launcherActivity = manifestInfo.activities.firstOrNull { it.isLauncher }?.className,
            activityComponents = manifestInfo.activities,
            receiverComponents = manifestInfo.receivers,
            providerComponents = manifestInfo.providers
        )
    }

    private fun parseManifestInfo(apkPath: String, packageName: String): ManifestParseResult {
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assetManager, apkPath) as? Int ?: return ManifestParseResult()
        if (cookie == 0) return ManifestParseResult()
        val parserMethod = AssetManager::class.java.getMethod(
            "openXmlResourceParser",
            Int::class.javaPrimitiveType,
            String::class.java
        )
        val parser = runCatching {
            parserMethod.invoke(assetManager, cookie, "AndroidManifest.xml") as XmlResourceParser
        }.getOrNull() ?: return ManifestParseResult()
        return parser.useManifestParser(packageName)
    }

    private fun XmlResourceParser.useManifestParser(packageName: String): ManifestParseResult =
        use {
            val activities = mutableListOf<ActivityComponentInfo>()
            val receivers = mutableListOf<ReceiverComponentInfo>()
            val providers = mutableListOf<ProviderComponentInfo>()
            var currentActivity: MutableActivityComponent? = null
            var currentReceiver: MutableReceiverComponent? = null
            var currentFilter: MutableIntentFilter? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (name) {
                        "activity" -> {
                            currentActivity = MutableActivityComponent(
                                className = resolveComponentName(packageName, attributeValue("name"))
                            )
                        }
                        "activity-alias" -> {
                            currentActivity = MutableActivityComponent(
                                className = resolveComponentName(
                                    packageName,
                                    attributeValue("targetActivity") ?: attributeValue("name")
                                )
                            )
                        }
                        "receiver" -> {
                            currentReceiver = MutableReceiverComponent(
                                className = resolveComponentName(packageName, attributeValue("name"))
                            )
                        }
                        "provider" -> {
                            val authority = attributeValue("authorities")
                            val className = resolveComponentName(packageName, attributeValue("name"))
                            if (authority != null && className.isNotBlank()) {
                                providers += ProviderComponentInfo(
                                    className = className,
                                    authority = authority
                                )
                            }
                        }
                        "intent-filter" -> currentFilter = MutableIntentFilter()
                        "action" -> currentFilter?.actions?.addNotBlank(attributeValue("name"))
                        "category" -> currentFilter?.categories?.addNotBlank(attributeValue("name"))
                        "data" -> currentFilter?.dataSpecs?.add(
                            IntentDataSpec(
                                scheme = attributeValue("scheme"),
                                host = attributeValue("host"),
                                mimeType = attributeValue("mimeType")
                            )
                        )
                    }

                    XmlPullParser.END_TAG -> when (name) {
                        "intent-filter" -> {
                            val filter = currentFilter?.toSpec()
                            if (filter != null) {
                                currentActivity?.intentFilters?.add(filter)
                                currentReceiver?.intentFilters?.add(filter)
                                if (
                                    Intent.ACTION_MAIN in filter.actions &&
                                    Intent.CATEGORY_LAUNCHER in filter.categories
                                ) {
                                    currentActivity?.isLauncher = true
                                }
                            }
                            currentFilter = null
                        }
                        "activity", "activity-alias" -> {
                            currentActivity?.toComponentInfo()?.takeIf {
                                it.className.isNotBlank()
                            }?.let(activities::add)
                            currentActivity = null
                        }
                        "receiver" -> {
                            currentReceiver?.toComponentInfo()?.takeIf {
                                it.className.isNotBlank()
                            }?.let(receivers::add)
                            currentReceiver = null
                        }
                    }
                }
                next()
            }

            ManifestParseResult(
                activities = activities,
                receivers = receivers,
                providers = providers
            )
        }

    private fun XmlPullParser.attributeValue(name: String): String? =
        getAttributeValue(ANDROID_NAMESPACE, name)

    private fun resolveComponentName(packageName: String, rawName: String?): String {
        val value = rawName?.trim().orEmpty()
        if (value.isBlank()) return value
        return when {
            value.startsWith('.') -> "$packageName$value"
            '.' !in value -> "$packageName.$value"
            else -> value
        }
    }

    private data class ManifestParseResult(
        val activities: List<ActivityComponentInfo> = emptyList(),
        val receivers: List<ReceiverComponentInfo> = emptyList(),
        val providers: List<ProviderComponentInfo> = emptyList()
    )

    private data class MutableActivityComponent(
        val className: String,
        val intentFilters: MutableList<IntentFilterSpec> = mutableListOf(),
        var isLauncher: Boolean = false
    ) {
        fun toComponentInfo() = ActivityComponentInfo(
            className = className,
            intentFilters = intentFilters.toList(),
            isLauncher = isLauncher
        )
    }

    private data class MutableReceiverComponent(
        val className: String,
        val intentFilters: MutableList<IntentFilterSpec> = mutableListOf()
    ) {
        fun toComponentInfo() = ReceiverComponentInfo(
            className = className,
            intentFilters = intentFilters.toList()
        )
    }

    private data class MutableIntentFilter(
        val actions: MutableList<String> = mutableListOf(),
        val categories: MutableList<String> = mutableListOf(),
        val dataSpecs: MutableList<IntentDataSpec> = mutableListOf()
    ) {
        fun toSpec() = IntentFilterSpec(
            actions = actions.distinct(),
            categories = categories.distinct(),
            dataSpecs = dataSpecs.toList()
        )
    }
}

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

private fun Drawable.toBitmapSafely(): Bitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return bitmap
    }
    val width = intrinsicWidth.takeIf { it > 0 } ?: 1
    val height = intrinsicHeight.takeIf { it > 0 } ?: 1
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}

private fun MutableList<String>.addNotBlank(value: String?) {
    value?.takeIf(String::isNotBlank)?.let(::add)
}
