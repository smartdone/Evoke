package com.smartdone.vm.stub

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

open class BaseStubService : Service() {
    private val binder = Messenger(
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                // Swallow guest service traffic for framework-style helpers like Firebase Sessions.
            }
        }
    ).binder

    override fun onBind(intent: Intent?): IBinder = binder
}

class StubService_P0 : BaseStubService()
class StubService_P1 : BaseStubService()
class StubService_P2 : BaseStubService()
class StubService_P3 : BaseStubService()
class StubService_P4 : BaseStubService()
class StubService_P5 : BaseStubService()
class StubService_P6 : BaseStubService()
class StubService_P7 : BaseStubService()
class StubService_P8 : BaseStubService()
class StubService_P9 : BaseStubService()
