package com.smartdone.vm.core.virtual.aidl;

interface IAppRequestHandler {
    void onPermissionResult(String packageName, String permissionName, boolean granted);
    void onLaunchRequested(String packageName, int userId);
}
