package org.fedorahosted.freeotp.ui.components

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onImeAction
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.R as MaterialR
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.util.AuthenticationUtil

/**
 * Controller for [SecurePassphraseField] that extracts passphrases directly into a [CharArray]
 * from Android's mutable [android.text.Editable] buffer without creating immutable JVM [String]s,
 * and overwrites the buffer with '\u0000' on clear/dispose.
 */
@Stable
class SecurePassphraseController {
    internal var editText: TextInputEditText? = null

    fun extractCharArray(): CharArray {
        val target = editText ?: return CharArray(0)
        return AuthenticationUtil.extractCharArray(target)
    }

    fun scrubAndCancelAutofill() {
        val target = editText ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AuthenticationUtil.clearAndCancelAutofill(target.context, target)
        } else {
            Handler(Looper.getMainLooper()).post {
                AuthenticationUtil.clearAndCancelAutofill(target.context, target)
            }
        }
    }

    internal fun replaceTextForTesting(text: CharSequence) {
        editText?.setText(text)
    }
}

@Composable
fun rememberSecurePassphraseController(): SecurePassphraseController {
    return remember { SecurePassphraseController() }
}

/**
 * Zero-[String] Material 3 password input backed by [TextInputLayout] + [TextInputEditText].
 *
 * - Real user typing mutates [android.text.Editable] in-place with `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`
 *   and `IME_FLAG_NO_PERSONALIZED_LEARNING` (never allocating heap [String] snapshots per keystroke).
 * - Exposes Compose `setText` semantics (without exposing plaintext in `editableText`) so
 *   `ComposeTestRule.onNodeWithTag(...).performTextReplacement(...)` works deterministically in UI tests.
 * - Automatically scrubs the [android.text.Editable] buffer with '\u0000' and cancels autofill on disposal.
 */
@Composable
fun SecurePassphraseField(
    controller: SecurePassphraseController,
    hint: String,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    errorTestTag: String = UiTestTags.SECURE_KEY_ERROR,
    imeAction: Int = EditorInfo.IME_ACTION_DONE,
    enabled: Boolean = true,
    testTag: String = UiTestTags.SECURE_KEY_INPUT,
    onTextChanged: () -> Unit = {},
    onImeAction: () -> Unit = {},
) {
    val onSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val onSurfaceVariantArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val outlineArgb = MaterialTheme.colorScheme.outline.toArgb()
    val errorArgb = MaterialTheme.colorScheme.error.toArgb()
    val noPersonalizedLearningFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    } else {
        0
    }

    var isFocused by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        onDispose {
            controller.scrubAndCancelAutofill()
            controller.editText = null
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
                .semantics {
                    password()
                    isEditable = enabled
                    editableText = AnnotatedString("")
                    textSelectionRange = TextRange.Zero
                    focused = isFocused
                    if (!enabled) {
                        disabled()
                    }
                    onClick {
                        controller.editText?.requestFocus()
                        isFocused = true
                        true
                    }
                    requestFocus {
                        controller.editText?.requestFocus()
                        isFocused = true
                        true
                    }
                    setText { annotated ->
                        controller.replaceTextForTesting(annotated.text)
                        true
                    }
                    insertTextAtCursor { annotated ->
                        val editable = controller.editText?.text
                        if (editable != null) {
                            editable.append(annotated.text)
                        } else {
                            controller.replaceTextForTesting(annotated.text)
                        }
                        true
                    }
                    setSelection { _, _, _ ->
                        true
                    }
                    onImeAction(ImeAction.Done) {
                        onImeAction()
                        true
                    }
                },
            factory = { ctx ->
                val themedContext = ContextThemeWrapper(ctx, R.style.AppTheme)
                val layout = TextInputLayout(
                    themedContext,
                    null,
                    MaterialR.attr.textInputOutlinedStyle,
                ).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ViewCompat.setImportantForAutofill(
                            this,
                            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                        )
                    }
                }

                val input = TextInputEditText(layout.context).apply {
                    id = View.generateViewId()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    isSingleLine = true
                    imeOptions = imeAction or noPersonalizedLearningFlag
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ViewCompat.setImportantForAutofill(
                            this,
                            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                        )
                    }
                    doAfterTextChanged {
                        onTextChanged()
                    }
                    setOnFocusChangeListener { _, hasFocus ->
                        isFocused = hasFocus
                    }
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == imeAction || actionId == EditorInfo.IME_ACTION_DONE) {
                            onImeAction()
                            true
                        } else {
                            false
                        }
                    }
                }

                layout.addView(input)
                controller.editText = input
                layout
            },
            update = { layout ->
                layout.hint = hint
                layout.isEnabled = enabled
                val strokeColor = if (errorMessage != null) errorArgb else primaryArgb
                layout.boxStrokeColor = strokeColor
                layout.setEndIconTintList(android.content.res.ColorStateList.valueOf(onSurfaceVariantArgb))
                layout.defaultHintTextColor = android.content.res.ColorStateList.valueOf(
                    if (errorMessage != null) errorArgb else outlineArgb
                )
                val input = controller.editText
                input?.isEnabled = enabled
                input?.setTextColor(onSurfaceArgb)
                input?.imeOptions = imeAction or noPersonalizedLearningFlag
            },
        )

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(start = 16.dp, top = 4.dp, end = 16.dp)
                    .testTag(errorTestTag),
            )
        }
    }
}
