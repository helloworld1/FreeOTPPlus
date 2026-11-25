package org.fedorahosted.freeotp.ui.tokenlist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.ui.MainViewModel
import org.fedorahosted.freeotp.ui.components.OtpProgressRing
import org.fedorahosted.freeotp.ui.components.TokenAvatar
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.ui.theme.OtpCodeCompactTextStyle
import org.fedorahosted.freeotp.ui.theme.OtpCodeTextStyle
import org.fedorahosted.freeotp.util.ThemeMode

private const val HOTP_COOLDOWN_MS = 5_000L
internal val TokenCardShape = RoundedCornerShape(16.dp)

@Composable
fun TokenCard(
    token: OtpToken,
    activeEntry: MainViewModel.ActiveTokenCodeEntry?,
    imageRefreshVersion: Long,
    isDragging: Boolean,
    onCardClick: (OtpToken) -> Unit,
    onCodeExpired: (Long) -> Unit,
    onEdit: (OtpToken) -> Unit,
    onDelete: (OtpToken) -> Unit,
    onShareToOtherApps: (OtpToken) -> Unit,
    onShareQrCode: (OtpToken) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val placeholder = remember(token.digits) { TokenDisplayUtil.buildPlaceholder(token.digits) }

    var displayedCode by remember(token.id) { mutableStateOf<String?>(null) }
    var currentProgress by remember(token.id) { mutableIntStateOf(0) }
    var totalProgress by remember(token.id) { mutableIntStateOf(0) }
    var isTapEnabled by remember(token.id) { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(token.id, activeEntry?.generation, activeEntry?.code, lifecycleOwner) {
        val codes = activeEntry?.code
        if (codes == null) {
            displayedCode = null
            currentProgress = 0
            totalProgress = 0
            isTapEnabled = true
            return@LaunchedEffect
        }

        val startTimeMs = System.currentTimeMillis()
        if (token.tokenType == OtpTokenType.HOTP) {
            isTapEnabled = false
        }

        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val raw = codes.currentCode
                if (raw == null) {
                    displayedCode = null
                    currentProgress = 0
                    totalProgress = 0
                    isTapEnabled = true
                    onCodeExpired(token.id)
                    break
                }
                displayedCode = TokenDisplayUtil.formatCodeForDisplay(raw)
                currentProgress = codes.currentProgress
                totalProgress = codes.totalProgress
                if (!isTapEnabled && System.currentTimeMillis() - startTimeMs > HOTP_COOLDOWN_MS) {
                    isTapEnabled = true
                }
                delay(200L)
            }
        }
    }

    val isActive = displayedCode != null
    val avatarAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.14f else 1f,
        label = "tokenAvatarAlpha",
    )

    val containerColor = if (isDragging) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(TokenCardShape)
            .clickable(enabled = isTapEnabled && !isDragging) { onCardClick(token) }
            .testTag(UiTestTags.tokenCardTag(token.id)),
        shape = TokenCardShape,
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
        border = BorderStroke(
            width = if (isDragging) 1.5.dp else 1.dp,
            color = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 104.dp)
                .padding(start = 12.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TokenAvatar(
                        token = token,
                        size = 64.dp,
                        alpha = avatarAlpha,
                        imageRefreshVersion = imageRefreshVersion,
                        modifier = Modifier.testTag(UiTestTags.tokenAvatarTag(token.id)),
                    )

                    if (isActive) {
                        OtpProgressRing(
                            currentProgress = currentProgress,
                            totalProgress = totalProgress,
                            isHotp = token.tokenType == OtpTokenType.HOTP,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            val textToDisplay = displayedCode ?: placeholder
            val codeStyle = if (textToDisplay.length >= 9) OtpCodeCompactTextStyle else OtpCodeTextStyle

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = textToDisplay,
                    style = codeStyle,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.testTag(UiTestTags.tokenCodeTag(token.id)),
                )

                if (token.issuer.isNullOrBlank()) {
                    Text(
                        text = token.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = token.issuer.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (token.label.isNotEmpty()) {
                        Text(
                            text = token.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (token.tokenType == OtpTokenType.HOTP) {
                val displayCounter = remember(token.counter) {
                    TokenDisplayUtil.formatHotpCounter(token.counter)
                }
                val counterDesc = "${stringResource(R.string.counter)}: $displayCounter"
                Surface(
                    modifier = Modifier
                        .padding(start = 6.dp, end = 2.dp)
                        .semantics { contentDescription = counterDesc }
                        .testTag(UiTestTags.tokenCounterContainerTag(token.id)),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 38.dp)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.counter_symbol),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = displayCounter,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.testTag(UiTestTags.tokenCounterValueTag(token.id)),
                        )
                    }
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag(UiTestTags.tokenMenuButtonTag(token.id)),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert_24px),
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.edit)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.edit_16px),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit(token)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.delete)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_24px),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete(token)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.share_to_other_apps)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_share_24px),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onShareToOtherApps(token)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.share_qr_code)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_qr_code_24px),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onShareQrCode(token)
                        },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TokenCardLightPreview() {
    val sampleHotp = OtpTokenFactory.create(
        type = OtpTokenType.HOTP,
        issuer = "GitHub",
        label = "octocat@github.com",
        secret = "JBSWY3DPEHPK3PXP",
        initialCounter = 42L,
    ).copy(id = 1L)
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(12.dp)) {
            TokenCard(
                token = sampleHotp,
                activeEntry = null,
                imageRefreshVersion = 0L,
                isDragging = false,
                onCardClick = {},
                onCodeExpired = {},
                onEdit = {},
                onDelete = {},
                onShareToOtherApps = {},
                onShareQrCode = {},
            )
        }
    }
}
