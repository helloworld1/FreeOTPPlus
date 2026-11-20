package org.fedorahosted.freeotp.data

enum class TokenSortOrder {
    ACCOUNT_ASC,
    ACCOUNT_DESC,
    ISSUER_ASC,
    ISSUER_DESC;

    fun sort(tokens: List<OtpToken>): List<OtpToken> {
        val sorted = tokens.sortedWith(comparator())
        return sorted.mapIndexed { index, token ->
            token.copy(ordinal = index.toLong())
        }
    }

    private fun comparator(): Comparator<OtpToken> = Comparator { a, b ->
        val aLabel = a.label.trim()
        val bLabel = b.label.trim()
        val aIssuerRaw = a.issuer?.trim().orEmpty()
        val bIssuerRaw = b.issuer?.trim().orEmpty()

        val primary = when (this) {
            ACCOUNT_ASC -> {
                val byAccount = String.CASE_INSENSITIVE_ORDER.compare(aLabel, bLabel)
                if (byAccount != 0) {
                    byAccount
                } else {
                    val byIssuer = String.CASE_INSENSITIVE_ORDER.compare(aIssuerRaw, bIssuerRaw)
                    if (byIssuer != 0) {
                        byIssuer
                    } else {
                        val exactAccount = aLabel.compareTo(bLabel)
                        if (exactAccount != 0) exactAccount else aIssuerRaw.compareTo(bIssuerRaw)
                    }
                }
            }

            ACCOUNT_DESC -> {
                val byAccount = String.CASE_INSENSITIVE_ORDER.compare(bLabel, aLabel)
                if (byAccount != 0) {
                    byAccount
                } else {
                    val byIssuer = String.CASE_INSENSITIVE_ORDER.compare(bIssuerRaw, aIssuerRaw)
                    if (byIssuer != 0) {
                        byIssuer
                    } else {
                        val exactAccount = bLabel.compareTo(aLabel)
                        if (exactAccount != 0) exactAccount else bIssuerRaw.compareTo(aIssuerRaw)
                    }
                }
            }

            ISSUER_ASC -> {
                val aIssuer = aIssuerRaw.takeIf { it.isNotEmpty() }
                val bIssuer = bIssuerRaw.takeIf { it.isNotEmpty() }
                when {
                    aIssuer == null && bIssuer != null -> 1
                    aIssuer != null && bIssuer == null -> -1
                    aIssuer != null && bIssuer != null -> {
                        val byIssuer = String.CASE_INSENSITIVE_ORDER.compare(aIssuer, bIssuer)
                        if (byIssuer != 0) {
                            byIssuer
                        } else {
                            val byAccount = String.CASE_INSENSITIVE_ORDER.compare(aLabel, bLabel)
                            if (byAccount != 0) {
                                byAccount
                            } else {
                                val exactIssuer = aIssuer.compareTo(bIssuer)
                                if (exactIssuer != 0) exactIssuer else aLabel.compareTo(bLabel)
                            }
                        }
                    }
                    else -> {
                        val byAccount = String.CASE_INSENSITIVE_ORDER.compare(aLabel, bLabel)
                        if (byAccount != 0) byAccount else aLabel.compareTo(bLabel)
                    }
                }
            }

            ISSUER_DESC -> {
                val aIssuer = aIssuerRaw.takeIf { it.isNotEmpty() }
                val bIssuer = bIssuerRaw.takeIf { it.isNotEmpty() }
                when {
                    aIssuer == null && bIssuer != null -> 1
                    aIssuer != null && bIssuer == null -> -1
                    aIssuer != null && bIssuer != null -> {
                        val byIssuer = String.CASE_INSENSITIVE_ORDER.compare(bIssuer, aIssuer)
                        if (byIssuer != 0) {
                            byIssuer
                        } else {
                            val byAccount = String.CASE_INSENSITIVE_ORDER.compare(bLabel, aLabel)
                            if (byAccount != 0) {
                                byAccount
                            } else {
                                val exactIssuer = bIssuer.compareTo(aIssuer)
                                if (exactIssuer != 0) exactIssuer else bLabel.compareTo(aLabel)
                            }
                        }
                    }
                    else -> {
                        val byAccount = String.CASE_INSENSITIVE_ORDER.compare(bLabel, aLabel)
                        if (byAccount != 0) byAccount else bLabel.compareTo(aLabel)
                    }
                }
            }
        }

        when {
            primary != 0 -> primary
            a.ordinal != b.ordinal -> a.ordinal.compareTo(b.ordinal)
            else -> a.id.compareTo(b.id)
        }
    }
}
