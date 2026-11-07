package com.twilightecho.android.nativeintegration.usb

internal object UsbAudioFixtures {
    fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    fun le(value: Long, size: Int) = ByteArray(size) { (value ushr (it * 8)).toByte() }
    private fun desc(vararg fields: Int) = bytes(fields.size + 1, *fields)
    fun iface(number: Int, alternate: Int, endpoints: Int, subclass: Int, protocol: Int) =
        desc(4, number, alternate, endpoints, 1, subclass, protocol, 0)

    fun config(value: Int = 1, parts: List<ByteArray>): ByteArray {
        val body = parts.fold(byteArrayOf()) { a, b -> a + b }
        val interfaces = parts.filter { it.size >= 9 && it[1].toInt() == 4 }.map { it[2] }.distinct().size
        return bytes(9, 2) + le((9 + body.size).toLong(), 2) + bytes(interfaces, value, 0, 0x80, 50) + body
    }

    fun uac1Parts(control: Int = 0, stream: Int = 1, rates: List<Int> = listOf(44100, 48000),
        subslot: Int = 3, bits: Int = 24, continuous: Boolean = false): List<ByteArray> {
        val format = bytes(if (continuous) 14 else 8 + rates.size * 3, 0x24, 2, 1, 2, subslot, bits,
            if (continuous) 0 else rates.size) + rates.fold(byteArrayOf()) { data, rate -> data + le(rate.toLong(), 3) }
        return listOf(
            iface(control, 0, 0, 1, 0),
            desc(0x24, 1, 0, 1, 40, 0, 1, stream),
            desc(0x24, 2, 1, 1, 1, 0, 2, 3, 0, 0, 0),
            desc(0x24, 6, 2, 1, 1, 3, 2, 2, 0),
            desc(0x24, 3, 3, 1, 3, 0, 2, 0),
            iface(stream, 0, 0, 2, 0), iface(stream, 1, 2, 2, 0),
            desc(0x24, 1, 1, 1, 1, 0), format,
            desc(5, 1, 5, 0x26, 1, 1, 0, 0x81),
            desc(0x25, 1, 1, 0, 0, 0),
            desc(5, 0x81, 0x11, 3, 0, 1, 4, 0),
        )
    }

    fun uac2Parts(control: Int = 4, stream: Int = 5, formats: Long = 1): List<ByteArray> = listOf(
        desc(11, control, 2, 1, 0, 0x20, 0), iface(control, 0, 0, 1, 0x20),
        desc(0x24, 1, 0, 2, 1, 88, 0, 0),
        desc(0x24, 2, 1, 1, 1, 0, 30, 2, 3, 0, 0, 0, 0, 0, 0, 0),
        desc(0x24, 0x0a, 10, 3, 7, 0, 0), desc(0x24, 0x0a, 11, 1, 1, 0, 0),
        desc(0x24, 0x0b, 20, 2, 10, 11, 3, 0), desc(0x24, 0x0c, 30, 20, 5, 0),
        desc(0x24, 6, 2, 1, 15, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0, 0),
        desc(0x24, 3, 3, 1, 3, 0, 2, 30, 0, 0, 0),
        iface(stream, 0, 0, 2, 0x20), iface(stream, 1, 2, 2, 0x20),
        bytes(16, 0x24, 1, 1, 0, 1) + le(formats, 4) + bytes(2, 3, 0, 0, 0, 0),
        desc(0x24, 2, 1, 4, 24), desc(5, 2, 5, 0, 4, 1),
        desc(0x25, 1, 0, 0, 0, 0, 0), desc(5, 0x82, 0x11, 4, 0, 4),
    )

    fun uac1() = UsbAudioDescriptors.parse(config(parts = uac1Parts())).configurations.single().functions.single()
    fun uac2() = UsbAudioDescriptors.parse(config(parts = uac2Parts())).configurations.single().functions.single()
}
