package org.fedorahosted.freeotp.token

import org.fedorahosted.freeotp.token.Token

/**
 * The saved tokens to storage
 */
data class SavedTokens(val tokens: List<Token>, val tokenOrder: List<String>)
