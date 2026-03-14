package com.smartdone.vm.core.virtual.util

import com.smartdone.vm.core.virtual.model.ActivityComponentInfo
import com.smartdone.vm.core.virtual.model.ApkMetadata
import com.smartdone.vm.core.virtual.model.IntentDataSpec
import com.smartdone.vm.core.virtual.model.IntentFilterSpec
import com.smartdone.vm.core.virtual.model.IntentMatchRequest
import com.smartdone.vm.core.virtual.model.ReceiverComponentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestIntentMatcherTest {
    private val metadata = ApkMetadata(
        packageName = "com.demo.app",
        label = "Demo",
        applicationClassName = null,
        versionCode = 1,
        requestedPermissions = emptyList(),
        activities = listOf("com.demo.app.MainActivity", "com.demo.app.ShareActivity"),
        services = emptyList(),
        providers = emptyList(),
        receivers = listOf("com.demo.app.BootReceiver", "com.demo.app.ShareReceiver"),
        iconBitmap = null,
        launcherActivity = "com.demo.app.MainActivity",
        activityComponents = listOf(
            ActivityComponentInfo(
                className = "com.demo.app.MainActivity",
                intentFilters = listOf(
                    IntentFilterSpec(
                        actions = listOf("android.intent.action.MAIN"),
                        categories = listOf("android.intent.category.LAUNCHER")
                    ),
                    IntentFilterSpec(
                        actions = listOf("android.intent.action.VIEW"),
                        categories = listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                        dataSpecs = listOf(
                            IntentDataSpec(scheme = "https", host = "vm.smartdone.app")
                        )
                    )
                ),
                isLauncher = true
            ),
            ActivityComponentInfo(
                className = "com.demo.app.ShareActivity",
                intentFilters = listOf(
                    IntentFilterSpec(
                        actions = listOf("android.intent.action.SEND"),
                        categories = listOf("android.intent.category.DEFAULT"),
                        dataSpecs = listOf(IntentDataSpec(mimeType = "image/*"))
                    )
                )
            ),
            ActivityComponentInfo(
                className = "com.demo.app.WebFallbackActivity",
                intentFilters = listOf(
                    IntentFilterSpec(
                        actions = listOf("android.intent.action.VIEW"),
                        categories = listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                        dataSpecs = listOf(IntentDataSpec(scheme = "https"))
                    )
                )
            )
        ),
        receiverComponents = listOf(
            ReceiverComponentInfo(
                className = "com.demo.app.BootReceiver",
                intentFilters = listOf(
                    IntentFilterSpec(actions = listOf("android.intent.action.BOOT_COMPLETED"))
                )
            ),
            ReceiverComponentInfo(
                className = "com.demo.app.ShareReceiver",
                intentFilters = listOf(
                    IntentFilterSpec(actions = listOf("custom.action.SHARE"))
                )
            )
        )
    )

    @Test
    fun resolveActivity_prefersLauncherForMainIntent() {
        val resolved = ManifestIntentMatcher.resolveActivity(
            metadata,
            IntentMatchRequest(
                action = "android.intent.action.MAIN",
                categories = setOf("android.intent.category.LAUNCHER")
            )
        )

        assertEquals("com.demo.app.MainActivity", resolved?.className)
    }

    @Test
    fun resolveActivity_matchesDeepLinkAndMimeRoutes() {
        val deepLink = ManifestIntentMatcher.resolveActivity(
            metadata,
            IntentMatchRequest(
                action = "android.intent.action.VIEW",
                categories = setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                scheme = "https",
                host = "vm.smartdone.app"
            )
        )
        val share = ManifestIntentMatcher.resolveActivity(
            metadata,
            IntentMatchRequest(
                action = "android.intent.action.SEND",
                categories = setOf("android.intent.category.DEFAULT"),
                mimeType = "image/png"
            )
        )

        assertEquals("com.demo.app.MainActivity", deepLink?.className)
        assertEquals("com.demo.app.ShareActivity", share?.className)
    }

    @Test
    fun resolveActivity_returnsNullWhenFiltersDoNotMatch() {
        val resolved = ManifestIntentMatcher.resolveActivity(
            metadata,
            IntentMatchRequest(
                action = "android.intent.action.VIEW",
                categories = setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                scheme = "ftp",
                host = "not-smartdone.app"
            )
        )

        assertNull(resolved)
    }

    @Test
    fun resolveReceivers_returnsOnlyMatchingActions() {
        val resolved = ManifestIntentMatcher.resolveReceivers(
            metadata,
            IntentMatchRequest(action = "android.intent.action.BOOT_COMPLETED")
        )

        assertEquals(listOf("com.demo.app.BootReceiver"), resolved.map { it.className })
    }

    @Test
    fun resolveActivity_returnsNullWhenNoComponentsExist() {
        val resolved = ManifestIntentMatcher.resolveActivity(
            metadata.copy(activityComponents = emptyList(), activities = emptyList(), launcherActivity = null),
            IntentMatchRequest(action = "custom.action.MISSING")
        )

        assertNull(resolved)
    }
}
