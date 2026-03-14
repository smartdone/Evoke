package com.smartdone.vm.core.virtual.client

class BridgeClassLoader(
    private val hostClassLoader: ClassLoader,
    private val hookClassWhitelist: Set<String>
) : ClassLoader(hostClassLoader.parent) {
    private val hostPrefixes = setOf(
        "com.smartdone.vm.core.virtual.client.hook.",
        "com.smartdone.vm.core.virtual.client.",
        "com.smartdone.vm.core.nativeengine."
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (
            name.startsWith("android.") ||
            name.startsWith("dalvik.") ||
            name.startsWith("java.") ||
            name.startsWith("javax.") ||
            name.startsWith("kotlin.") ||
            name.startsWith("org.json.") ||
            name.startsWith("org.w3c.") ||
            name.startsWith("org.xml.sax.") ||
            name.startsWith("org.xmlpull.")
        ) {
            return super.loadClass(name, resolve)
        }
        if (name in hookClassWhitelist || hostPrefixes.any(name::startsWith)) {
            return hostClassLoader.loadClass(name)
        }
        throw ClassNotFoundException(name)
    }
}
