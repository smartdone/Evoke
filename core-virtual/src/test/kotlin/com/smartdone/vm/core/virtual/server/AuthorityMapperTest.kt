package com.smartdone.vm.core.virtual.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorityMapperTest {
    @Test
    fun createRoute_usesDeclaredStubAuthority() {
        val route = AuthorityMapper.createRoute(
            hostPackage = "com.smartdone.vm",
            packageName = "com.demo.app",
            originalAuthority = "com.demo.provider",
            slotId = 3
        )

        assertEquals("com.smartdone.vm.vcp.p3", route.stubAuthority)
        assertEquals("com.demo.provider", route.originalAuthority)
        assertEquals("com.demo.app", route.packageName)
    }

    @Test
    fun toHostUri_preservesOriginalAuthorityAndUserIsolationMetadata() {
        val route = AuthorityMapper.createRoute(
            hostPackage = "com.smartdone.vm",
            packageName = "com.demo.app",
            originalAuthority = "com.demo.provider",
            slotId = 1
        )

        val mapped = AuthorityMapper.appendIsolationMetadata(
            originalUri = "content://com.smartdone.vm.vcp.p1/users",
            route = route,
            userId = 7
        )

        assertEquals("com.demo.provider", AuthorityMapper.originalAuthority(mapped))
        assertEquals("com.demo.app", AuthorityMapper.packageName(mapped))
        assertEquals(7, AuthorityMapper.userId(mapped))
    }

    @Test
    fun metadataReaders_returnDefaultsWhenMetadataMissing() {
        val plain = "content://plain.authority/value"

        assertNull(AuthorityMapper.originalAuthority(plain))
        assertNull(AuthorityMapper.packageName(plain))
        assertEquals(0, AuthorityMapper.userId(plain))
    }
}
