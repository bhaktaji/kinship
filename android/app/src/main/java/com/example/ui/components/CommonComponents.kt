package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class MainTab(val route: String, val label: String, val testTag: String) {
    DISCOVER("discover", "Discover", "nav_tab_discover"),
    GROUPS("groups", "My Groups", "nav_tab_groups"),
    MATCHES("matches", "Matches", "nav_tab_matches"),
    PROFILE("profile", "Profile", "nav_tab_profile")
}

@DrawableRes
fun categoryDrawableRes(category: String?): Int {
    return when (category?.lowercase(Locale.ROOT)?.trim()) {
        "music" -> R.drawable.ic_category_music
        "sport", "sports", "fitness" -> R.drawable.ic_category_sport
        "outdoors", "nature", "hiking" -> R.drawable.ic_category_outdoors
        "food", "drink", "coffee", "cooking" -> R.drawable.ic_category_food
        "books", "reading", "writing", "study" -> R.drawable.ic_category_books
        "art", "craft", "design", "photography" -> R.drawable.ic_category_art
        else -> R.drawable.ic_category_default
    }
}

fun categoryDisplayName(category: String?): String {
    return when (category?.lowercase(Locale.ROOT)?.trim()) {
        "music" -> "Music"
        "sport", "sports", "fitness" -> "Sport"
        "outdoors", "nature", "hiking" -> "Outdoors"
        "food", "drink", "coffee", "cooking" -> "Food & Drink"
        "books", "reading", "writing", "study" -> "Books & Ideas"
        "art", "craft", "design", "photography" -> "Art & Craft"
        else -> category?.replaceFirstChar { it.uppercase() }?.ifBlank { "Community" } ?: "Community"
    }
}

/**
 * Renders the editorial category artwork vector drawable recreated from `web/components/CategoryArt.tsx`.
 * Uses zero remote images — 100% local Android VectorDrawable assets.
 */
@Composable
fun CategoryArtworkBanner(
    category: String?,
    modifier: Modifier = Modifier,
    height: Dp = 136.dp,
    badgeText: String? = null,
    trailingBadge: String? = null
) {
    val drawableId = categoryDrawableRes(category)
    val label = badgeText ?: categoryDisplayName(category)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        Image(
            painter = painterResource(id = drawableId),
            contentDescription = "$label category illustration",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                color = SurfaceCream.copy(alpha = 0.92f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, LineBorder)
            ) {
                Text(
                    text = label.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 0.7.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = InkDark,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            if (!trailingBadge.isNullOrBlank()) {
                Surface(
                    color = InkDark.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = trailingBadge,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = SurfaceCream,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

enum class PillTone {
    TERRACOTTA,
    FOREST,
    SAGE,
    GOLD,
    DANGER,
    MUTED
}

@Composable
fun StatusPill(
    text: String,
    tone: PillTone = PillTone.MUTED,
    modifier: Modifier = Modifier
) {
    val (bg, fg, border) = when (tone) {
        PillTone.TERRACOTTA -> Triple(TerracottaSoft, TerracottaBrand, TerracottaBrand.copy(alpha = 0.25f))
        PillTone.FOREST -> Triple(Color(0xFFE5EFE9), ForestPrimary, ForestPrimary.copy(alpha = 0.25f))
        PillTone.SAGE -> Triple(Color(0xFFEBF0E8), SageAccent, SageAccent.copy(alpha = 0.25f))
        PillTone.GOLD -> Triple(Color(0xFFFDF3E1), WarningAmber, GoldHighlight.copy(alpha = 0.3f))
        PillTone.DANGER -> Triple(Color(0xFFFCEBE9), DangerRed, DangerRed.copy(alpha = 0.25f))
        PillTone.MUTED -> Triple(PaperCanvas, MutedText, LineBorder)
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, border),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun CapacityMeter(
    confirmedCount: Int,
    capacity: Int,
    modifier: Modifier = Modifier
) {
    val safeCapacity = capacity.coerceAtLeast(1)
    val progress = (confirmedCount.toFloat() / safeCapacity.toFloat()).coerceIn(0f, 1f)
    val isFull = confirmedCount >= safeCapacity
    val isNearlyFull = !isFull && (safeCapacity - confirmedCount) <= 1

    val barColor = when {
        isFull -> DangerRed
        isNearlyFull -> GoldHighlight
        else -> ForestPrimary
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$confirmedCount / $safeCapacity spots filled",
                style = MaterialTheme.typography.labelMedium,
                color = if (isFull) DangerRed else MutedText
            )
            Text(
                text = when {
                    isFull -> "Full"
                    isNearlyFull -> "1 spot left"
                    else -> "${safeCapacity - confirmedCount} open"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = barColor
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(LineBorder.copy(alpha = 0.65f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
fun AvatarCircle(
    displayName: String,
    size: Dp = 38.dp,
    modifier: Modifier = Modifier
) {
    val palette = listOf(
        ForestPrimary,
        TerracottaBrand,
        SageAccent,
        GoldHighlight,
        InfoBlue,
        Color(0xFF7A4E3B)
    )
    val colorIndex = abs(displayName.hashCode()) % palette.size
    val bg = palette[colorIndex]
    val initials = displayName
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "K" }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelMedium.copy(
                color = SurfaceCream,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

enum class BannerTone {
    ERROR,
    WARNING,
    SUCCESS,
    INFO
}

@Composable
fun AlertBanner(
    message: String,
    tone: BannerTone = BannerTone.INFO,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (bg, fg, border, icon) = when (tone) {
        BannerTone.ERROR -> Quadruple(Color(0xFFFCEBE9), DangerRed, Color(0xFFF2C3BE), Icons.Default.ErrorOutline)
        BannerTone.WARNING -> Quadruple(Color(0xFFFDF3E1), WarningAmber, Color(0xFFF2D6A2), Icons.Default.WarningAmber)
        BannerTone.SUCCESS -> Quadruple(Color(0xFFE6F3EC), SuccessGreen, Color(0xFFB7DEC8), Icons.Default.CheckCircleOutline)
        BannerTone.INFO -> Quadruple(Color(0xFFEAF1F8), InfoBlue, Color(0xFFBDD2EA), Icons.Default.Info)
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, border),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = tone.name,
                tint = fg,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = fg,
                modifier = Modifier.weight(1f)
            )
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss alert",
                        tint = fg,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun AppTopHeader(
    unreadCount: Int,
    pendingOutboxCount: Int,
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit,
    userDisplayName: String?,
    backTitle: String? = null,
    onBack: (() -> Unit)? = null
) {
    Surface(
        color = PaperCanvas.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (onBack != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("header_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Navigate back",
                                tint = InkDark
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = backTitle ?: "Kinship",
                            style = MaterialTheme.typography.titleLarge,
                            color = InkDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(TerracottaBrand, ForestPrimary)
                                    )
                                )
                        ) {
                            Text(
                                text = "K",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = SurfaceCream,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Kinship",
                                style = MaterialTheme.typography.titleLarge,
                                color = InkDark
                            )
                            Text(
                                text = "Small-group activities",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedText
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (pendingOutboxCount > 0) {
                        StatusPill(
                            text = "$pendingOutboxCount queued RSVP",
                            tone = PillTone.GOLD
                        )
                    }

                    IconButton(
                        onClick = onOpenNotifications,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("notifications_bell_button")
                    ) {
                        BadgedBox(
                            badge = {
                                if (unreadCount > 0) {
                                    Badge(
                                        containerColor = TerracottaBrand,
                                        contentColor = SurfaceCream
                                    ) {
                                        Text(
                                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (unreadCount > 0) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                                contentDescription = "Notifications ($unreadCount unread)",
                                tint = InkDark
                            )
                        }
                    }

                    if (!userDisplayName.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onOpenProfile)
                                .testTag("header_avatar_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            AvatarCircle(displayName = userDisplayName, size = 34.dp)
                        }
                    }
                }
            }
            HorizontalDivider(color = LineBorder, thickness = 1.dp)
        }
    }
}

@Composable
fun KinshipBottomNavBar(
    currentTab: MainTab?,
    onSelectTab: (MainTab) -> Unit
) {
    Surface(
        color = SurfaceCream,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(color = LineBorder, thickness = 1.dp)
            NavigationBar(
                containerColor = SurfaceCream,
                tonalElevation = 0.dp
            ) {
                MainTab.entries.forEach { tab ->
                    val selected = currentTab == tab
                    val icon = when (tab) {
                        MainTab.DISCOVER -> if (selected) Icons.Filled.Explore else Icons.Outlined.Explore
                        MainTab.GROUPS -> if (selected) Icons.Filled.Groups else Icons.Outlined.Groups
                        MainTab.MATCHES -> if (selected) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome
                        MainTab.PROFILE -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelectTab(tab) },
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TerracottaBrand,
                            selectedTextColor = TerracottaBrand,
                            indicatorColor = TerracottaSoft,
                            unselectedIconColor = MutedText,
                            unselectedTextColor = MutedText
                        ),
                        modifier = Modifier.testTag(tab.testTag)
                    )
                }
            }
        }
    }
}

fun formatIsoDateTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "Flexible schedule"
    return try {
        val instant = Instant.parse(iso)
        val formatter = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm", Locale.US)
            .withZone(ZoneId.of("Europe/Berlin"))
        formatter.format(instant)
    } catch (_: Exception) {
        iso.take(16).replace("T", " · ")
    }
}

fun formatDistanceLabel(distanceMeters: Int): String {
    return if (distanceMeters < 1000) {
        "${distanceMeters}m away"
    } else {
        String.format(Locale.US, "%.1f km away", distanceMeters / 1000.0)
    }
}
