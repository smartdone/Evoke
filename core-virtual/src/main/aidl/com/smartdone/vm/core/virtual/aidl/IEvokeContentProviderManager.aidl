package com.smartdone.vm.core.virtual.aidl;

import android.os.Bundle;

interface IEvokeContentProviderManager {
    Bundle acquireProvider(String authority);
    Bundle getProviderInfo(String authority);
}
