package com.smartdone.vm.core.virtual.aidl;

import android.os.Bundle;

interface IEvokeActivityManager {
    Bundle startActivity(String packageName, int userId, String activityName);
    Bundle startService(String packageName, int userId, String serviceName);
    Bundle bindService(String packageName, int userId, String serviceName);
    void reportAppLaunch(String packageName, int userId, int pid);
}
