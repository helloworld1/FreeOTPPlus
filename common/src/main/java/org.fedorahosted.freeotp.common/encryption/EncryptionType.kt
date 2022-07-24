package org.fedorahosted.freeotp.common.encryption

enum class EncryptionType {
    PLAIN_TEXT,
    // By default new Tokens are encrypted
    AES,
}