package com.smartdone.vm.core.virtual.aidl;

interface IPermissionDelegate {
    boolean requestPermission(String packageName, String permissionName);
    boolean checkPermission(String packageName, String permissionName);
    List<String> getGrantedPermissions(String packageName);
}
