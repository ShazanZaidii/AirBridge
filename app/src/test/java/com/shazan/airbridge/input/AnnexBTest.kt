package com.shazan.airbridge.input

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnexBTest {
    @Test fun splitsThreeAndFourByteStartCodes() {
        val b = byteArrayOf(
            9, 9, // header bytes before offset
            0, 0, 0, 1, 0x67, 1, 2, // SPS (4-byte start code)
            0, 0, 1, 0x68, 3, // PPS (3-byte start code)
            0, 0, 0, 1, 0x65, 4, 5, 6, // IDR
        )
        val units = nalUnits(b, 2).map { (s, e) -> b.copyOfRange(s, e).toList() }
        assertEquals(
            listOf(listOf<Byte>(0x67, 1, 2), listOf<Byte>(0x68, 3), listOf<Byte>(0x65, 4, 5, 6)),
            units,
        )
    }
}
