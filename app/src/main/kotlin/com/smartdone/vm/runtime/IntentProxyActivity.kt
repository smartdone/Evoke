package com.smartdone.vm.runtime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.smartdone.vm.core.virtual.EvokeCore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IntentProxyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CoroutineScope(Dispatchers.Main).launch {
            val launched = entryPoint().evokeCore().launchIntent(intent, userId = 0)
            if (!launched && !intent.getBooleanExtra(EXTRA_FALLBACK_ATTEMPTED, false)) {
                runCatching {
                    startActivity(
                        Intent(intent).apply {
                            component = null
                            `package` = null
                            putExtra(EXTRA_FALLBACK_ATTEMPTED, true)
                        }
                    )
                }
            }
            finish()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface IntentProxyEntryPoint {
        fun evokeCore(): EvokeCore
    }

    private fun entryPoint(): IntentProxyEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, IntentProxyEntryPoint::class.java)

    companion object {
        private const val EXTRA_FALLBACK_ATTEMPTED = "vm_intent_fallback_attempted"
    }
}
