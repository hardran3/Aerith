package com.aerith.core.nostr

object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    fun decode(bech32: String): Pair<String, ByteArray> {
        var lower = false
        var upper = false
        for (i in bech32.indices) {
            val c = bech32[i]
            if (c in 'a'..'z') lower = true
            else if (c in 'A'..'Z') upper = true
            else if (c !in '0'..'9') throw IllegalArgumentException("Invalid character")
        }
        if (lower && upper) throw IllegalArgumentException("Mixed case")
        val pos = bech32.lastIndexOf('1')
        if (pos < 1) throw IllegalArgumentException("Missing separator")
        if (pos + 7 > bech32.length) throw IllegalArgumentException("Too short")

        val hrp = bech32.substring(0, pos).lowercase()
        val data = IntArray(bech32.length - pos - 1)
        for (i in 0 until data.size) {
            val d = CHARSET.indexOf(bech32[pos + 1 + i].lowercaseChar())
            if (d == -1) throw IllegalArgumentException("Invalid character")
            data[i] = d
        }

        if (!verifyChecksum(hrp, data)) throw IllegalArgumentException("Invalid checksum")

        val ret = convertBits(data.sliceArray(0 until data.size - 6), 5, 8, false)
        return Pair(hrp, ret)
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        return polymod(expandHrp(hrp) + data) == 1
    }

    private fun expandHrp(hrp: String): IntArray {
        val ret = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            val c = hrp[i].code
            ret[i] = c shr 5
            ret[i + hrp.length + 1] = c and 31
        }
        ret[hrp.length] = 0
        return ret
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk shr 25
            chk = (chk and 0x1ffffff shl 5) xor v
            if ((b shr 0) and 1 == 1) chk = chk xor 0x3b6a57b2
            if ((b shr 1) and 1 == 1) chk = chk xor 0x26508e6d
            if ((b shr 2) and 1 == 1) chk = chk xor 0x1ea119fa
            if ((b shr 3) and 1 == 1) chk = chk xor 0x3d4233dd
            if ((b shr 4) and 1 == 1) chk = chk xor 0x2a1462b3
        }
        return chk
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val ret = java.io.ByteArrayOutputStream()
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
        for (value in data) {
            if (value < 0 || (value shr fromBits) != 0) {
                throw IllegalArgumentException("Invalid value")
            }
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret.write((acc shr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) {
                ret.write((acc shl (toBits - bits)) and maxv)
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw IllegalArgumentException("Invalid padding")
        }
        return ret.toByteArray()
    }
    
    fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
