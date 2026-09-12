package com.ruirui.findme.models

data class EncryptedMessage(
    // The sender's current public Diffie-Hellman key (triggers the DH Ratchet)
    val ratchetKey: ByteArray,

    // The index of this message in the current sending chain
    val msgNumber: Int,

    // How many messages were in the previous chain (helps the receiver know exactly how many messages they missed when the ratchet turns)
    val previousChainLength: Int,

    // The actual AES-256-GCM encrypted location payload
    val ciphertext: ByteArray,
)