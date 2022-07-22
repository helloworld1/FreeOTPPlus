package org.fedorahosted.freeotp.data

enum class EncryptionType {
    PLAIN_TEXT,
    // By default new Tokens are encrypted
    AES
}