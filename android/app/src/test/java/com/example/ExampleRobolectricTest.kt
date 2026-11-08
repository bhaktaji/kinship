package com.example

import com.example.data.repository.DemoSeedCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleRobolectricTest {

    @Test
    fun `seed catalog contains Berlin activities and demo user`() {
        assertEquals("demo@kinship.local", DemoSeedCatalog.demoUser.email)
        assertTrue(DemoSeedCatalog.seededActivities.isNotEmpty())
        assertTrue(DemoSeedCatalog.berlinPresets.isNotEmpty())
    }
}
