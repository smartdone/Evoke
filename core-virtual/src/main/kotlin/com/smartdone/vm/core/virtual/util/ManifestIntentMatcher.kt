package com.smartdone.vm.core.virtual.util

import android.content.Intent
import com.smartdone.vm.core.virtual.model.ActivityComponentInfo
import com.smartdone.vm.core.virtual.model.ApkMetadata
import com.smartdone.vm.core.virtual.model.IntentDataSpec
import com.smartdone.vm.core.virtual.model.IntentFilterSpec
import com.smartdone.vm.core.virtual.model.IntentMatchRequest
import com.smartdone.vm.core.virtual.model.ReceiverComponentInfo

object ManifestIntentMatcher {
    data class ActivityResolution(
        val component: ActivityComponentInfo,
        val score: Int
    )

    fun fromIntent(intent: Intent): IntentMatchRequest =
        IntentMatchRequest(
            action = intent.action,
            categories = intent.categories.orEmpty(),
            scheme = intent.data?.scheme,
            host = intent.data?.host,
            mimeType = intent.type,
            targetPackage = intent.`package` ?: intent.component?.packageName
        )

    fun resolveActivity(metadata: ApkMetadata, request: IntentMatchRequest): ActivityComponentInfo? =
        resolveActivityResolution(metadata, request)?.component

    fun resolveActivityResolution(
        metadata: ApkMetadata,
        request: IntentMatchRequest
    ): ActivityResolution? {
        if (request.action == Intent.ACTION_MAIN && Intent.CATEGORY_LAUNCHER in request.categories) {
            metadata.activityComponents.firstOrNull(ActivityComponentInfo::isLauncher)?.let {
                return ActivityResolution(component = it, score = Int.MAX_VALUE)
            }
        }
        val resolutions = metadata.activityComponents.mapNotNull { component ->
            val score = component.intentFilters.mapNotNull { matchScore(it, request) }.maxOrNull()
                ?: return@mapNotNull null
            ActivityResolution(component = component, score = score)
        }
        if (resolutions.isNotEmpty()) {
            return resolutions.maxWithOrNull(
                compareBy<ActivityResolution>({ it.score }, { if (it.component.isLauncher) 1 else 0 })
            )
        }
        return null
    }

    fun resolveReceivers(metadata: ApkMetadata, request: IntentMatchRequest): List<ReceiverComponentInfo> =
        metadata.receiverComponents.filter { component ->
            component.intentFilters.any { matches(it, request) }
        }

    fun matches(filter: IntentFilterSpec, request: IntentMatchRequest): Boolean {
        if (filter.actions.isNotEmpty()) {
            val action = request.action ?: return false
            if (action !in filter.actions) return false
        }

        if (request.categories.isNotEmpty() && !request.categories.all { it in filter.categories }) {
            return false
        }

        if (filter.dataSpecs.isEmpty()) return true
        return filter.dataSpecs.any { dataSpec -> matchesData(dataSpec, request) }
    }

    private fun matchScore(filter: IntentFilterSpec, request: IntentMatchRequest): Int? {
        if (!matches(filter, request)) return null
        var score = 0
        if (request.action != null && request.action in filter.actions) score += 8
        score += request.categories.count { it in filter.categories } * 2
        if (filter.dataSpecs.isEmpty()) return score + 1
        return filter.dataSpecs
            .filter { matchesData(it, request) }
            .maxOfOrNull { dataSpec ->
                score +
                    when {
                        dataSpec.scheme == request.scheme && dataSpec.host == request.host -> 12
                        dataSpec.host == request.host && dataSpec.host != null -> 10
                        dataSpec.scheme == request.scheme && dataSpec.scheme != null -> 6
                        dataSpec.mimeType != null -> 4
                        else -> 2
                    }
            }
    }

    private fun matchesData(dataSpec: IntentDataSpec, request: IntentMatchRequest): Boolean {
        if (dataSpec.scheme != null && dataSpec.scheme != request.scheme) return false
        if (dataSpec.host != null && dataSpec.host != request.host) return false
        if (dataSpec.mimeType != null && !mimeMatches(dataSpec.mimeType, request.mimeType)) return false
        if (dataSpec.scheme == null && dataSpec.host == null && dataSpec.mimeType == null) return true
        return true
    }

    private fun mimeMatches(filterMime: String, requestMime: String?): Boolean {
        val mimeType = requestMime ?: return false
        if (filterMime == "*/*" || filterMime == mimeType) return true
        val filterParts = filterMime.split('/', limit = 2)
        val requestParts = mimeType.split('/', limit = 2)
        if (filterParts.size != 2 || requestParts.size != 2) return false
        if (filterParts[0] == "*" && filterParts[1] == "*") return true
        if (filterParts[0] == requestParts[0] && filterParts[1] == "*") return true
        return false
    }
}
