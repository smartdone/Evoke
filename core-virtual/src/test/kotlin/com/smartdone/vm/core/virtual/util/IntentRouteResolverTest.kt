package com.smartdone.vm.core.virtual.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentRouteResolverTest {
    @Test
    fun resolvePackageName_prefersExplicitComponentPackage() {
        assertEquals(
            "com.demo.app",
            IntentRouteResolver.resolvePackageName(
                componentPackage = "com.demo.app",
                intentPackage = "com.other.app",
                extraPackage = "com.third.app",
                dataString = "vm://launch?vm_pkg=com.query.pkg"
            )
        )
    }

    @Test
    fun resolvePackageName_usesPackageAndExtrasAndQueryFallbacks() {
        assertEquals(
            "com.demo.pkg",
            IntentRouteResolver.resolvePackageName(intentPackage = "com.demo.pkg")
        )
        assertEquals(
            "com.extra.pkg",
            IntentRouteResolver.resolvePackageName(extraPackage = "com.extra.pkg")
        )
        assertEquals(
            "com.query.pkg",
            IntentRouteResolver.resolvePackageName(dataString = "vm://launch?vm_pkg=com.query.pkg")
        )
    }

    @Test
    fun resolvePackageName_returnsNullWhenNoHintsExist() {
        assertNull(IntentRouteResolver.resolvePackageName())
    }
}
