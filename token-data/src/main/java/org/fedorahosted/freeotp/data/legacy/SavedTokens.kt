package org.fedorahosted.freeotp.data.legacy

import org.fedorahosted.freeotp.data.legacy.Token

/**
 * The saved tokens to storage
 */
data class SavedTokens(val tokens: List<Token>, val tokenOrder: List<String>)
