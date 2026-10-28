package com.twilightecho.android.nativeintegration

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeDspConfigTest {
    @Test fun mapsPhoneEqAndGaplessToSharedDspContract() {
        val obj = Json.parseToJsonElement(nativeDspConfig(
            """{"enabled":true,"preampDb":-6,"bandsDb":[1,2,3,4,5,6,7,8,9,10],"gapless":false,"bitPerfect":false}""", Json)).jsonObject
        assertTrue(obj.getValue("dspEnabled").jsonPrimitive.boolean)
        assertTrue(obj.getValue("eqEnabled").jsonPrimitive.boolean)
        assertFalse(obj.getValue("gapless").jsonPrimitive.boolean)
        assertEquals(-6.0, obj.getValue("eqPreamp").jsonPrimitive.double, 0.0)
        val bands = obj.getValue("eqBands").jsonArray
        assertEquals(10, bands.size)
        assertEquals(31.0, bands.first().jsonObject.getValue("frequency").jsonPrimitive.double, 0.0)
        assertEquals(16000.0, bands.last().jsonObject.getValue("frequency").jsonPrimitive.double, 0.0)
        assertEquals(10.0, bands.last().jsonObject.getValue("gain").jsonPrimitive.double, 0.0)
    }
    @Test fun bypassDoesNotInventBitPerfectAndGraphPassesThrough() {
        val obj = Json.parseToJsonElement(nativeDspConfig("""{"enabled":false,"bandsDb":[],"bitPerfect":true}""", Json)).jsonObject
        assertFalse(obj.getValue("dspEnabled").jsonPrimitive.boolean)
        assertFalse("outputPerfect" in obj)
        val graph = """{"dspEnabled":true,"graph":{"nodes":[]}}"""
        assertEquals(graph, nativeDspConfig(graph, Json))
    }
}
