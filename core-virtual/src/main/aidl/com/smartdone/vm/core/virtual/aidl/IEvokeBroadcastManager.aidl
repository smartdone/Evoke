package com.smartdone.vm.core.virtual.aidl;

interface IEvokeBroadcastManager {
    void registerStaticReceivers(String packageName, in List<String> receivers);
    void registerDynamicReceiver(String packageName, String receiverClassName);
    List<String> getReceivers(String packageName);
    List<String> dispatchBroadcast(String action, String targetPackage);
}
