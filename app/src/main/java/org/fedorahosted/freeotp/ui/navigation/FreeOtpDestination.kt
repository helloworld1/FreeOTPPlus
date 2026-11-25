package org.fedorahosted.freeotp.ui.navigation

object FreeOtpDestination {
    const val TOKEN_LIST = "token_list"
    const val SCAN_TOKEN = "scan_token"
    const val ADD_TOKEN = "add_token"
    const val EDIT_TOKEN = "edit_token/{tokenId}"
    const val ABOUT = "about"

    const val ARG_TOKEN_ID = "tokenId"

    fun editToken(tokenId: Long): String = "edit_token/$tokenId"
}
