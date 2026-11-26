package org.fedorahosted.freeotp.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.style.URLSpan
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.util.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionText = remember(context) { resolveAboutVersionText(context) }
    val linkColor = MaterialTheme.colorScheme.primary

    val websiteHtml = stringResource(R.string.about_website, stringResource(R.string.link_github))
    val licenseHtml = stringResource(R.string.about_license, stringResource(R.string.link_apache2))
    val tokenImageHtml = stringResource(R.string.about_token_image)

    val websiteAnnotated = remember(websiteHtml, linkColor) {
        htmlToLinkedAnnotatedString(websiteHtml, linkColor) { openExternalUrlSafely(context, it) }
    }
    val licenseAnnotated = remember(licenseHtml, linkColor) {
        htmlToLinkedAnnotatedString(licenseHtml, linkColor) { openExternalUrlSafely(context, it) }
    }
    val tokenImageAnnotated = remember(tokenImageHtml, linkColor) {
        htmlToLinkedAnnotatedString(tokenImageHtml, linkColor) { openExternalUrlSafely(context, it) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about_freeotp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag(UiTestTags.NAV_BACK_BUTTON),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back_24px),
                            contentDescription = stringResource(R.string.cancel_text),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                    Image(
                        painter = painterResource(R.drawable.logo),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = versionText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTestTags.ABOUT_VERSION),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.about_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = websiteAnnotated,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(UiTestTags.ABOUT_WEBSITE),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 14.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        Text(
                            text = licenseAnnotated,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(UiTestTags.ABOUT_LICENSE),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 14.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        Text(
                            text = stringResource(R.string.about_icons),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 14.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )

                        Text(
                            text = tokenImageAnnotated,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(UiTestTags.ABOUT_TOKEN_IMAGES),
                        )
                    }
                }
            }
        }
    }
}

internal fun resolveAboutVersionText(context: Context): String {
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        context.getString(
            R.string.about_version,
            info.versionName ?: "",
            PackageInfoCompat.getLongVersionCode(info),
        )
    }.getOrElse {
        context.getString(R.string.about_version, "3.7", 29L)
    }
}

internal fun htmlToLinkedAnnotatedString(
    html: String,
    linkColor: Color,
    onOpenUrl: (String) -> Unit,
): AnnotatedString {
    val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
    val plain = spanned.toString()
    val urlSpans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
    return buildAnnotatedString {
        append(plain)
        for (span in urlSpans) {
            val start = spanned.getSpanStart(span).coerceAtLeast(0)
            val end = spanned.getSpanEnd(span).coerceAtMost(plain.length)
            val url = span.url
            if (start < end && !url.isNullOrBlank()) {
                addLink(
                    url = LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium,
                            )
                        ),
                        linkInteractionListener = {
                            onOpenUrl(url)
                        },
                    ),
                    start = start,
                    end = end,
                )
            }
        }
    }
}

private fun openExternalUrlSafely(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Ignore gracefully when no browser activity is installed
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenLightPreview() {
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        AboutScreen(onNavigateBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenDarkPreview() {
    FreeOtpTheme(themeMode = ThemeMode.DARK) {
        AboutScreen(onNavigateBack = {})
    }
}
