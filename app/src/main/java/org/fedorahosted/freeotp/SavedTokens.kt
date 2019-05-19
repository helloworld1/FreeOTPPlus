package org.fedorahosted.freeotp

/**
 * The saved tokens to storage
 */
data class SavedTokens(val tokens: List<Token>, val tokenOrder: List<String>)
