package com.smartdone.vm.core.virtual.aidl;

import android.os.Bundle;

interface IEvokePackageManager {
    Bundle getPackageInfo(String packageName);
    List<String> getInstalledPackages();
    Bundle getApplicationInfo(String packageName);
    Bundle resolveIntent(String action, String packageName);
    Bundle resolveIntentRoute(in Bundle request);
}
