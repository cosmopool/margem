package com.kaiodelphino.margem

import kotlin.test.Test
import kotlin.test.assertEquals

/** FIPS 180-4 vectors, the padding edges, and the dedupe hash (D32). */
class Sha256Test {

    private fun hex(s: String) = sha256(s.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `nist vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            hex("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"),
        )
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hex("a".repeat(1_000_000)))
    }

    @Test
    fun `lengths around the 64-byte block`() {
        assertEquals("9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318", hex("a".repeat(55)))
        assertEquals("b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a", hex("a".repeat(56)))
        assertEquals("ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb", hex("a".repeat(64)))
    }

    @Test
    fun `content hash - each part prefixed by its length so moving text between fields changes it`() {
        assertEquals(
            "afda7d4c7a42989c9bf6eaaabdf015ff7c72e2499e72077874cdc2e6020aa6a4",
            contentHash(listOf("Card purchase", "You spent \$4.50 at Coffee", "", "", "")),
        )
        assertEquals("bf2a676355968a4e126dd956d1da8537671ea92264687d0384f0a9d8ab7f8582", contentHash(listOf("\u0000\u0000", "É", "", "", "")))
        assertEquals("09796f801da9edfbef227d57ace945815cdae2143c4a16ae4222205438296474", contentHash(listOf("\u0000", "\u0000É", "", "", "")))
    }

    @Test
    fun `content hash - lengths of 256 bytes and more use every byte of the prefix`() {
        assertEquals(
            "ccb21e826a1e3ddb2eb4c471e5574d7112f5562b67d1bdd6fec4492977fc2217",
            contentHash(listOf("a".repeat(300), "b".repeat(70_000), "", "", "")),
        )
    }
}
