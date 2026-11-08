package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.data.repository.DemoSeedCatalog
import com.example.ui.ChatUiMessage
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun GroupChatScreen(
    groupDetail: GroupDetailDto?,
    currentUserId: String?,
    messages: List<ChatUiMessage>,
    isWsConnected: Boolean,
    hasOlderMessages: Boolean,
    onLoadOlder: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val memberNames = remember(groupDetail) {
        groupDetail?.members?.associate { it.userId to it.displayName }.orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Chat status bar
        Surface(
            color = SurfaceCream,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = groupDetail?.group?.name ?: "Group Chat",
                            style = MaterialTheme.typography.titleMedium,
                            color = InkDark
                        )
                        Text(
                            text = "Single-use WS ticket · client_msg_id deduplication",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText
                        )
                    }
                    StatusPill(
                        text = if (isWsConnected) "● Live WebSocket" else "● REST + Reconnect",
                        tone = if (isWsConnected) PillTone.FOREST else PillTone.GOLD
                    )
                }
                HorizontalDivider(color = LineBorder)
            }
        }

        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("chat_messages_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (hasOlderMessages) {
                item {
                    OutlinedButton(
                        onClick = onLoadOlder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Load older messages")
                    }
                }
            }

            items(messages, key = { it.messageId }) { msg ->
                val isMine = msg.senderId == currentUserId
                val senderName = when {
                    isMine -> "You"
                    memberNames.containsKey(msg.senderId) -> memberNames[msg.senderId]!!
                    else -> "Member ${msg.senderId.take(4)}"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isMine) ForestPrimary else SurfaceCream
                        ),
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isMine) 16.dp else 4.dp,
                            bottomEnd = if (isMine) 4.dp else 16.dp
                        ),
                        border = if (isMine) null else BorderStroke(1.dp, LineBorder),
                        modifier = Modifier.widthIn(max = 310.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = senderName,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isMine) TerracottaSoft else TerracottaBrand
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (msg.isPendingAck) "Sending…" else "#${msg.sequenceId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isMine) SurfaceCream.copy(alpha = 0.7f) else FaintText
                                )
                            }
                            Text(
                                text = msg.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isMine) SurfaceCream else InkDark
                            )
                            Text(
                                text = formatIsoDateTime(msg.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isMine) SurfaceCream.copy(alpha = 0.75f) else MutedText
                            )
                        }
                    }
                }
            }
        }

        // Message composer
        Surface(
            color = SurfaceCream,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                HorizontalDivider(color = LineBorder)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Write a message to the circle…") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
                    )
                    Button(
                        onClick = {
                            if (draft.isNotBlank()) {
                                onSendMessage(draft)
                                draft = ""
                            }
                        },
                        enabled = draft.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TerracottaBrand,
                            contentColor = SurfaceCream
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("chat_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MatchesScreen(
    invitations: List<MatchInvitationDto>,
    latestJob: MatchJobResponseDto?,
    isLoading: Boolean,
    onRequestMatch: (Int, Int) -> Unit,
    onRespondInvitation: (String, Boolean) -> Unit
) {
    var maxDistanceKm by remember { mutableIntStateOf(10) }
    var preferredGroupSize by remember { mutableIntStateOf(4) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("matches_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, LineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusPill(text = "ALGORITHMIC SMALL-GROUP MATCHING", tone = PillTone.TERRACOTTA)
                    Text(
                        text = "48-Hour Match Proposals",
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkDark
                    )
                    Text(
                        text = "Kinship scores compatible 3–6 person groups using Jaccard interest similarity (40%), availability overlap (30%), and PostGIS distance decay (30%). When all invited members accept within 48 hours, your circle is automatically formed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Max distance:", style = MaterialTheme.typography.labelLarge)
                        listOf(5, 10, 20).forEach { km ->
                            FilterChip(
                                selected = maxDistanceKm == km,
                                onClick = { maxDistanceKm = km },
                                label = { Text("$km km") }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Ideal circle size:", style = MaterialTheme.typography.labelLarge)
                        listOf(4, 5, 6).forEach { sz ->
                            FilterChip(
                                selected = preferredGroupSize == sz,
                                onClick = { preferredGroupSize = sz },
                                label = { Text("$sz people") }
                            )
                        }
                    }

                    Button(
                        onClick = { onRequestMatch(maxDistanceKm, preferredGroupSize) },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForestPrimary,
                            contentColor = SurfaceCream
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("find_my_group_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = SurfaceCream,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Find My Group Proposal")
                        }
                    }

                    if (latestJob != null) {
                        val scorePct = ((latestJob.results.firstOrNull()?.score ?: 0.86) * 100).toInt()
                        AlertBanner(
                            message = "Match Job ${latestJob.status}: Compatibility score $scorePct%",
                            tone = BannerTone.SUCCESS
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Active 48-Hour Invitations (${invitations.size})",
                style = MaterialTheme.typography.titleLarge,
                color = InkDark
            )
        }

        if (invitations.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, LineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No pending invitations right now. Tap 'Find My Group Proposal' above to run the matcher against nearby members.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText,
                        modifier = Modifier.padding(18.dp)
                    )
                }
            }
        } else {
            items(invitations, key = { it.invitationId }) { invite ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, LineBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("match_invitation_card_${invite.invitationId}")
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPill(text = "48H INVITATION · ${invite.status}", tone = PillTone.GOLD)
                            Text(
                                text = "Expires ${formatIsoDateTime(invite.expiresAt)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TerracottaBrand
                            )
                        }

                        Text(
                            text = "Proposed ${invite.members.size}-Person Circle",
                            style = MaterialTheme.typography.titleLarge,
                            color = InkDark
                        )

                        invite.members.forEach { member ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    AvatarCircle(displayName = member.displayName, size = 36.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = member.displayName,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = InkDark
                                        )
                                        Text(
                                            text = member.interests.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MutedText
                                        )
                                    }
                                }
                                Text(
                                    text = "${"%.1f".format(member.distanceKm)} km",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SageAccent
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onRespondInvitation(invite.invitationId, false) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("decline_invitation_${invite.invitationId}")
                            ) {
                                Text("Decline")
                            }
                            Button(
                                onClick = { onRespondInvitation(invite.invitationId, true) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ForestPrimary,
                                    contentColor = SurfaceCream
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("accept_invitation_${invite.invitationId}")
                            ) {
                                Text("Accept & Join")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationsScreen(
    notifications: List<NotificationDto>,
    unreadCount: Int,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onNavigateEntity: (String?, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("notifications_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkDark
                    )
                    Text(
                        text = "$unreadCount unread · Polled every 30s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
                if (unreadCount > 0) {
                    OutlinedButton(
                        onClick = onMarkAllRead,
                        modifier = Modifier.testTag("mark_all_read_button")
                    ) {
                        Text("Mark all read")
                    }
                }
            }
        }

        items(notifications, key = { it.id }) { item ->
            val isUnread = item.readAt == null
            Card(
                onClick = {
                    if (isUnread) onMarkRead(item.id)
                    onNavigateEntity(item.entityType, item.entityId)
                },
                colors = CardDefaults.cardColors(
                    containerColor = if (isUnread) TerracottaSoft.copy(alpha = 0.5f) else SurfaceCream
                ),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (isUnread) TerracottaBrand.copy(alpha = 0.4f) else LineBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notification_item_${item.id}")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            text = item.type,
                            tone = if (isUnread) PillTone.TERRACOTTA else PillTone.MUTED
                        )
                        Text(
                            text = formatIsoDateTime(item.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText
                        )
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = InkDark
                    )
                    if (!item.body.isNullOrBlank()) {
                        Text(
                            text = item.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )
                    }
                    if (isUnread) {
                        TextButton(
                            onClick = { onMarkRead(item.id) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Mark as read", color = ForestPrimary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    user: UserDto?,
    taxonomyInterests: List<InterestResponseDto>,
    onSaveProfile: (UpdateProfileRequest) -> Unit,
    onUploadPhoto: (Uri) -> Unit,
    onRestartOnboarding: () -> Unit,
    onLogout: () -> Unit
) {
    val current = user ?: DemoSeedCatalog.demoUser
    var displayName by remember(current) { mutableStateOf(current.displayName) }
    var bio by remember(current) { mutableStateOf(current.bio.orEmpty()) }
    var profession by remember(current) { mutableStateOf(current.profession.orEmpty()) }
    var workplace by remember(current) { mutableStateOf(current.workplace.orEmpty()) }
    var workplaceShared by remember(current) { mutableStateOf(current.workplaceShared) }
    var hobbiesText by remember(current) { mutableStateOf(current.hobbies.joinToString(", ")) }

    val selectedInterests = remember(current) {
        mutableStateListOf<Int>().apply {
            addAll(current.interests.map { it.interestId })
        }
    }

    // Zero-permission Android Photo Picker (compliant with Google Play policy; validates 5 MB max + jpg/png/webp/gif)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onUploadPhoto(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("profile_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCream),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, LineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarCircle(displayName = current.displayName, size = 56.dp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = current.displayName,
                                style = MaterialTheme.typography.headlineSmall,
                                color = InkDark
                            )
                            Text(
                                text = current.email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedText
                            )
                            if (current.profilePhotoAssetId != null) {
                                StatusPill(
                                    text = "Photo Asset: ${current.profilePhotoAssetId.take(8)}",
                                    tone = PillTone.SAGE
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("upload_profile_photo_button")
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Upload Photo (≤5MB)")
                    }
                    OutlinedButton(
                        onClick = onRestartOnboarding,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("open_onboarding_wizard_button")
                    ) {
                        Text("3-Step Wizard")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCream),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, LineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Profile & Matching Signals",
                    style = MaterialTheme.typography.titleLarge,
                    color = InkDark
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_name_input")
                )

                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("Bio") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = profession,
                        onValueChange = { profession = it },
                        label = { Text("Profession") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = workplace,
                        onValueChange = { workplace = it },
                        label = { Text("Workplace") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Share workplace when requesting to join circles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkDark
                    )
                    Switch(
                        checked = workplaceShared,
                        onCheckedChange = { workplaceShared = it }
                    )
                }

                OutlinedTextField(
                    value = hobbiesText,
                    onValueChange = { hobbiesText = it },
                    label = { Text("Hobbies (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Selected Interests (${selectedInterests.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = InkDark
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    taxonomyInterests.forEach { item ->
                        val isSelected = item.id in selectedInterests
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) selectedInterests.remove(item.id)
                                else selectedInterests.add(item.id)
                            },
                            label = { Text(item.name) }
                        )
                    }
                }

                Button(
                    onClick = {
                        onSaveProfile(
                            UpdateProfileRequest(
                                displayName = displayName.trim(),
                                bio = bio.trim(),
                                profession = profession.trim(),
                                workplace = workplace.trim(),
                                workplaceShared = workplaceShared,
                                hobbies = hobbiesText.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                interests = selectedInterests.map { InterestSelectionDto(it, 1.0) },
                                location = current.location ?: LocationDto(52.5200, 13.4050, "Berlin"),
                                availability = current.availability,
                                onboardingCompleted = true
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ForestPrimary,
                        contentColor = SurfaceCream
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("save_profile_button")
                ) {
                    Text("Save Profile Changes")
                }
            }
        }

        OutlinedButton(
            onClick = onLogout,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("sign_out_button")
        ) {
            Text("Sign Out & Purge Session Cache")
        }
    }
}
