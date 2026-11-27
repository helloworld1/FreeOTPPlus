package org.fedorahosted.freeotp.ui.components

/**
 * Centralized semantic test tags shared between Compose screens/dialogs and ComposeTestRule tests.
 */
object UiTestTags {
    // Top bar & overflow menus
    const val TOP_APP_BAR = "top_app_bar"
    const val APP_TITLE = "app_title"
    const val NAV_BACK_BUTTON = "nav_back_button"
    const val QUIT_AND_LOCK_BUTTON = "quit_and_lock_button"
    const val OVERFLOW_MENU_BUTTON = "overflow_menu_button"
    const val OVERFLOW_MENU = "overflow_menu"

    // Option A TopAppBar search & category filter dropdown on TokenListScreen
    const val SEARCH_BUTTON = "search_button"
    const val SEARCH_COLLAPSE_BUTTON = "search_collapse_button"
    const val SEARCH_FIELD = "search_field"
    const val SEARCH_CLEAR_BUTTON = "search_clear_button"
    const val CATEGORY_FILTER_BUTTON = "category_filter_button"
    const val CATEGORY_FILTER_MENU = "category_filter_menu"
    const val CATEGORY_FILTER_ALL = "category_filter_all"
    const val CATEGORY_FILTER_UNCATEGORIZED = "category_filter_uncategorized"

    fun categoryFilterItemTag(category: String): String = "category_filter_item_$category"

    // Token list & cards
    const val TOKEN_LIST = "token_list"
    const val EMPTY_VIEW = "empty_view"
    const val FAB_MENU_BUTTON = "fab_menu_button"
    const val FAB_MENU_SCRIM = "fab_menu_scrim"
    const val FAB_ADD_TOKEN_ITEM = "fab_add_token_item"
    const val FAB_PASTE_URI_ITEM = "fab_paste_uri_item"
    const val FAB_SCAN_QR_ITEM = "fab_scan_qr_item"
    const val SCAN_FAB = FAB_MENU_BUTTON

    fun tokenCardTag(tokenId: Long): String = "token_card_$tokenId"
    fun tokenMenuButtonTag(tokenId: Long): String = "token_menu_$tokenId"
    fun tokenAvatarTag(tokenId: Long): String = "token_avatar_$tokenId"
    fun tokenCodeTag(tokenId: Long): String = "token_code_$tokenId"
    fun tokenCounterContainerTag(tokenId: Long): String = "token_counter_container_$tokenId"
    fun tokenCounterValueTag(tokenId: Long): String = "token_counter_value_$tokenId"

    // Add / Edit Token screens
    const val TOKEN_AVATAR_BUTTON = "token_avatar_button"
    const val REMOVE_TOKEN_IMAGE_BUTTON = "remove_token_image_button"
    const val ISSUER_INPUT = "issuer_input"
    const val LABEL_INPUT = "label_input"
    const val SECRET_INPUT = "secret_input"
    const val GENERATE_SECRET_BUTTON = "generate_secret_button"
    const val TYPE_TOTP_BUTTON = "type_totp_button"
    const val TYPE_HOTP_BUTTON = "type_hotp_button"
    const val ALGORITHM_DROPDOWN = "algorithm_dropdown"
    const val INTERVAL_INPUT = "interval_input"
    const val COUNTER_INPUT = "counter_input"
    const val CATEGORY_FLOW_ROW = "category_flow_row"
    const val ADD_CATEGORY_CHIP = "add_category_chip"
    const val CANCEL_BUTTON = "cancel_button"
    const val ADD_SUBMIT_BUTTON = "add_submit_button"
    const val RESTORE_DEFAULTS_BUTTON = "restore_defaults_button"
    const val SAVE_BUTTON = "save_button"

    fun digitsButtonTag(digits: Int): String = "digits_button_$digits"
    fun editCategoryChipTag(category: String): String = "edit_category_chip_$category"

    // Edit Token details card
    const val DETAIL_ALGORITHM = "detail_algorithm"
    const val DETAIL_DIGITS = "detail_digits"
    const val DETAIL_INTERVAL_OR_COUNTER = "detail_interval_or_counter"
    const val DETAIL_SECRET = "detail_secret"
    const val COPY_SECRET_BUTTON = "copy_secret_button"

    // About screen
    const val ABOUT_VERSION = "about_version"
    const val ABOUT_WEBSITE = "about_website"
    const val ABOUT_LICENSE = "about_license"
    const val ABOUT_TOKEN_IMAGES = "about_token_images"

    // Scan QR screen
    const val SCAN_BACK_BUTTON = "scan_back_button"
    const val SCAN_PREVIEW = "scan_preview"
    const val SCAN_RETICLE = "scan_reticle"

    // Dialogs
    const val SHARE_QR_IMAGE = "share_qr_image"
    const val SHARE_QR_SUBTITLE = "share_qr_subtitle"
    const val COPY_URI_BUTTON = "copy_uri_button"
    const val DELETE_CONFIRM_BUTTON = "delete_confirm_button"
    const val DELETE_ISSUER = "delete_issuer"
    const val DELETE_LABEL = "delete_label"
    const val DELETE_CATEGORY = "delete_category"
    const val ADD_TOKEN_DIALOG_AVATAR = "add_token_dialog_avatar"
    const val ADD_TOKEN_DIALOG_ISSUER = "add_token_dialog_issuer"
    const val ADD_TOKEN_DIALOG_LABEL = "add_token_dialog_label"
    const val ADD_TOKEN_DIALOG_CATEGORY = "add_token_dialog_category"
    const val ICON_SEARCH_INPUT = "icon_search_input"
    const val ICON_GRID = "icon_grid"

    fun builtInIconItemTag(iconName: String): String = "builtin_icon_item_$iconName"
    const val CATEGORY_NAME_INPUT = "category_name_input"
    const val SECURE_KEY_INPUT = "secure_key_input"
    const val SECURE_KEY_ERROR = "secure_key_error"
    const val SECURE_CONFIRM_KEY_INPUT = "secure_confirm_key_input"
    const val SECURE_CONFIRM_KEY_ERROR = "secure_confirm_key_error"
    const val ENABLE_BIOMETRIC_CHECKBOX = "enable_biometric_checkbox"
    const val IMPORT_MODE_APPEND_RADIO = "import_mode_append_radio"
    const val IMPORT_MODE_REPLACE_ALL_RADIO = "import_mode_replace_all_radio"
    const val REPLACE_WARNING_TEXT = "replace_warning_text"
    const val DIALOG_POSITIVE_BUTTON = "dialog_positive_button"
    const val DIALOG_NEGATIVE_BUTTON = "dialog_negative_button"
    const val DIALOG_NEUTRAL_BUTTON = "dialog_neutral_button"
}
