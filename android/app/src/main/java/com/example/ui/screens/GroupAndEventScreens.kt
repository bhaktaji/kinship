package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun GroupsListScreen(
    groups: List<GroupCardDto>,
    onOpenGroup: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    onCreateGroup: (CreateGroupRequest) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("groups_list"),
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        StatusPill(text = "YOUR CIRCLES", tone = PillTone.FOREST)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "My Groups (${groups.size})",
                            style = MaterialTheme.typography.headlineMedium,
                            color = InkDark
                        )
                        Text(
                            text = "Start a new small group or jump into your active circles' chats and meetups.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { showCreateDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TerracottaBrand,
                            contentColor = SurfaceCream
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("create_group_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create group")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Group")
                    }
                }
            }
        }

        items(groups, key = { it.groupId }) { group ->
            Card(
                onClick = { onOpenGroup(group.groupId) },
                colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LineBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("group_card_${group.groupId}")
            ) {
                Column {
                    CategoryArtworkBanner(
                        category = group.category,
                        height = 112.dp,
                        trailingBadge = group.myStatus
                    )
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = InkDark
                            )
                            StatusPill(
                                text = group.visibility.uppercase(),
                                tone = if (group.visibility == "private") PillTone.GOLD else PillTone.SAGE
                            )
                        }

                        CapacityMeter(
                            confirmedCount = group.memberCount,
                            capacity = group.maxMembers
                        )

                        if (group.nextEventAt != null) {
                            Text(
                                text = "Next meetup: ${formatIsoDateTime(group.nextEventAt)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = TerracottaBrand
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onOpenGroup(group.groupId) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("Manage Circle")
                            }
                            if (group.myStatus == "ACTIVE") {
                                Button(
                                    onClick = { onOpenChat(group.groupId) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ForestPrimary,
                                        contentColor = SurfaceCream
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("open_chat_button_${group.groupId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = "Group chat",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Group Chat")
                                }
                            } else if (group.nextEventId != null) {
                                Button(
                                    onClick = { onOpenEvent(group.nextEventId) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ForestPrimary,
                                        contentColor = SurfaceCream
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                ) {
                                    Text("Next Event")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateDialog = false },
            onSubmit = { req ->
                showCreateDialog = false
                onCreateGroup(req)
            }
        )
    }
}

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onSubmit: (CreateGroupRequest) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("music") }
    var maxMembers by remember { mutableIntStateOf(6) }
    var visibility by remember { mutableStateOf("public") }
    var joinPolicy by remember { mutableStateOf("OPEN") }

    val categories = listOf("music", "sport", "outdoors", "food", "books", "art")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a Small Group", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_group_name_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description & ritual vibe") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Category", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.take(3).forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.drop(3).forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Max capacity: $maxMembers seats", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        listOf(4, 6, 8).forEach { cap ->
                            FilterChip(
                                selected = maxMembers == cap,
                                onClick = { maxMembers = cap },
                                label = { Text("$cap") },
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = joinPolicy == "OPEN",
                        onClick = {
                            joinPolicy = "OPEN"
                            visibility = "public"
                        },
                        label = { Text("Instant Join") }
                    )
                    FilterChip(
                        selected = joinPolicy == "APPROVAL",
                        onClick = {
                            joinPolicy = "APPROVAL"
                            visibility = "private"
                        },
                        label = { Text("Owner Approval") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.trim().length >= 3) {
                        onSubmit(
                            CreateGroupRequest(
                                name = name.trim(),
                                description = description.trim().ifBlank { "Weekly Berlin small-group meetup." },
                                category = category,
                                location = LocationDto(52.5200, 13.4050, "Berlin"),
                                maxMembers = maxMembers,
                                visibility = visibility,
                                joinPolicy = joinPolicy
                            )
                        )
                    }
                },
                enabled = name.trim().length >= 3,
                colors = ButtonDefaults.buttonColors(containerColor = ForestPrimary, contentColor = SurfaceCream),
                modifier = Modifier.testTag("confirm_create_group_button")
            ) {
                Text("Create Circle")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun GroupDetailScreen(
    detail: GroupDetailDto?,
    reviews: ReviewSummaryDto,
    onJoinGroup: (String) -> Unit,
    onRequestJoinApproval: (String, String, Boolean) -> Unit,
    onLeaveGroup: (String) -> Unit,
    onUpdateMemberStatus: (String, String, String) -> Unit,
    onCreateSeries: (String, CreateSeriesRequest) -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    onReportReview: (String, String) -> Unit
) {
    if (detail == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ForestPrimary)
        }
        return
    }

    val group = detail.group
    val isOwner = group.myRole == "OWNER"
    val isActiveMember = group.myStatus == "ACTIVE"
    val isPendingMember = group.myStatus == "PENDING"
    val requiresApproval = group.joinPolicy == "APPROVAL" || group.visibility == "private"

    var showJoinRequestDialog by remember { mutableStateOf(false) }
    var showCreateSeriesDialog by remember { mutableStateOf(false) }
    var reportingReviewId by remember { mutableStateOf<String?>(null) }

    val pendingMembers = detail.members.filter { it.status == "PENDING" }
    val activeMembers = detail.members.filter { it.status == "ACTIVE" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("group_detail_list"),
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
                Column {
                    CategoryArtworkBanner(
                        category = group.category,
                        height = 150.dp,
                        trailingBadge = "${group.visibility.uppercase()} · ${group.joinPolicy}"
                    )
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.headlineMedium,
                                color = InkDark,
                                modifier = Modifier.weight(1f)
                            )
                            if (group.myRole != null) {
                                StatusPill(
                                    text = "${group.myRole} (${group.myStatus})",
                                    tone = if (isOwner) PillTone.TERRACOTTA else PillTone.FOREST
                                )
                            }
                        }

                        if (!group.description.isNullOrBlank()) {
                            Text(
                                text = group.description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MutedText
                            )
                        }

                        CapacityMeter(
                            confirmedCount = group.activeMemberCount,
                            capacity = group.maxMembers
                        )

                        // Membership / Chat / Owner controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            when {
                                isActiveMember -> {
                                    Button(
                                        onClick = { onOpenChat(group.groupId) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ForestPrimary,
                                            contentColor = SurfaceCream
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("group_detail_open_chat_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ChatBubbleOutline,
                                            contentDescription = "Open group chat",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Open Group Chat")
                                    }

                                    if (isOwner) {
                                        FilledTonalButton(
                                            onClick = { showCreateSeriesDialog = true },
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = TerracottaSoft,
                                                contentColor = TerracottaBrand
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("owner_create_series_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Event,
                                                contentDescription = "Schedule recurring series",
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("New Series")
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { onLeaveGroup(group.groupId) },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("leave_group_button")
                                        ) {
                                            Text("Leave")
                                        }
                                    }
                                }

                                isPendingMember -> {
                                    AlertBanner(
                                        message = "Your membership request is pending approval from the organizer.",
                                        tone = BannerTone.WARNING
                                    )
                                }

                                requiresApproval -> {
                                    Button(
                                        onClick = { showJoinRequestDialog = true },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = TerracottaBrand,
                                            contentColor = SurfaceCream
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("request_join_approval_button")
                                    ) {
                                        Text("Request Approval to Join")
                                    }
                                }

                                else -> {
                                    Button(
                                        onClick = { onJoinGroup(group.groupId) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ForestPrimary,
                                            contentColor = SurfaceCream
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("join_group_instantly_button")
                                    ) {
                                        Text("Join Circle Instantly")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Owner Controls: Pending Approvals
        if (isOwner && pendingMembers.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = TerracottaSoft.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, TerracottaBrand.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        StatusPill(text = "OWNER CONTROLS · PENDING REQUESTS", tone = PillTone.TERRACOTTA)
                        pendingMembers.forEach { member ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AvatarCircle(displayName = member.displayName, size = 34.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = member.displayName,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = InkDark
                                        )
                                        Text(
                                            text = "Requested to join",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MutedText
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = {
                                            onUpdateMemberStatus(group.groupId, member.userId, "ACTIVE")
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ForestPrimary,
                                            contentColor = SurfaceCream
                                        ),
                                        modifier = Modifier.testTag("approve_member_${member.userId}")
                                    ) {
                                        Text("Approve")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onUpdateMemberStatus(group.groupId, member.userId, "REMOVED")
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed)
                                    ) {
                                        Text("Decline")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Upcoming Occurrences
        item {
            Text(
                text = "Upcoming Occurrences (${detail.nextEvents.size})",
                style = MaterialTheme.typography.titleLarge,
                color = InkDark
            )
        }

        if (detail.nextEvents.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, LineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isOwner) "No upcoming dates scheduled yet — tap 'New Series' above to generate recurring weekly or monthly meetups."
                        else "No upcoming occurrences scheduled yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(detail.nextEvents, key = { it.eventId }) { ev ->
                Card(
                    onClick = { onOpenEvent(ev.eventId) },
                    colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, LineBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_occurrence_card_${ev.eventId}")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = ev.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = InkDark
                            )
                            StatusPill(
                                text = ev.myStatus ?: "RSVP OPEN",
                                tone = if (ev.myStatus == "GOING") PillTone.FOREST else PillTone.TERRACOTTA
                            )
                        }
                        Text(
                            text = formatIsoDateTime(ev.startTime),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TerracottaBrand
                        )
                        CapacityMeter(confirmedCount = ev.confirmedCount, capacity = ev.capacity)
                    }
                }
            }
        }

        // Members section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Circle Members (${activeMembers.size}/${group.maxMembers})",
                        style = MaterialTheme.typography.titleLarge,
                        color = InkDark
                    )
                    activeMembers.forEach { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AvatarCircle(displayName = member.displayName, size = 36.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = member.displayName,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = InkDark
                                    )
                                    Text(
                                        text = member.role,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MutedText
                                    )
                                }
                            }
                            if (isOwner && member.role != "OWNER") {
                                TextButton(
                                    onClick = {
                                        onUpdateMemberStatus(group.groupId, member.userId, "REMOVED")
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = DangerRed)
                                ) {
                                    Text("Remove")
                                }
                            } else {
                                StatusPill(
                                    text = member.role,
                                    tone = if (member.role == "OWNER") PillTone.TERRACOTTA else PillTone.SAGE
                                )
                            }
                        }
                    }
                }
            }
        }

        // Reviews section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Circle Vibe & Reviews (${reviews.count})",
                            style = MaterialTheme.typography.titleLarge,
                            color = InkDark
                        )
                        if (reviews.avgOverall != null) {
                            StatusPill(
                                text = "★ ${"%.1f".format(reviews.avgOverall)} / 5.0",
                                tone = PillTone.GOLD
                            )
                        }
                    }

                    reviews.recent.forEach { rev ->
                        HorizontalDivider(color = LineBorder)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Overall ★${rev.overallRating} · Atmosphere ★${rev.atmosphereRating} · Org ★${rev.organizationRating}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = GoldHighlight
                                )
                                IconButton(
                                    onClick = { reportingReviewId = rev.id },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Flag,
                                        contentDescription = "Report review",
                                        tint = MutedText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            if (!rev.feedback.isNullOrBlank()) {
                                Text(
                                    text = "\"${rev.feedback}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkDark
                                )
                            }
                            if (rev.vibeTags.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    rev.vibeTags.forEach { tag ->
                                        StatusPill(text = tag, tone = PillTone.SAGE)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJoinRequestDialog) {
        var reason by remember { mutableStateOf("I'd love to join your weekly Berlin sessions and can attend regularly.") }
        var shareWorkplace by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showJoinRequestDialog = false },
            title = { Text("Request Approval to Join") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Introduce yourself to the group organizer:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Why would you like to join?") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Include workplace on request", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = shareWorkplace, onCheckedChange = { shareWorkplace = it })
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showJoinRequestDialog = false
                        onRequestJoinApproval(group.groupId, reason, shareWorkplace)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TerracottaBrand, contentColor = SurfaceCream)
                ) {
                    Text("Send Request")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoinRequestDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateSeriesDialog) {
        CreateRecurringSeriesDialog(
            defaultCapacity = group.maxMembers,
            onDismiss = { showCreateSeriesDialog = false },
            onSubmit = { req ->
                showCreateSeriesDialog = false
                onCreateSeries(group.groupId, req)
            }
        )
    }

    if (reportingReviewId != null) {
        var reportReason by remember { mutableStateOf("Off-topic or inappropriate content") }
        AlertDialog(
            onDismissRequest = { reportingReviewId = null },
            title = { Text("Report Review") },
            text = {
                OutlinedTextField(
                    value = reportReason,
                    onValueChange = { reportReason = it },
                    label = { Text("Reason for reporting") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = reportingReviewId!!
                        reportingReviewId = null
                        onReportReview(id, reportReason)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = SurfaceCream)
                ) {
                    Text("Submit Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { reportingReviewId = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CreateRecurringSeriesDialog(
    defaultCapacity: Int,
    onDismiss: () -> Unit,
    onSubmit: (CreateSeriesRequest) -> Unit
) {
    var title by remember { mutableStateOf("Weekly Evening Session") }
    var freq by remember { mutableStateOf("WEEKLY") }
    var byDay by remember { mutableStateOf("TH") }
    var startTime by remember { mutableStateOf("18:30") }
    var startsOn by remember { mutableStateOf("2026-11-12") }
    var durationMin by remember { mutableIntStateOf(90) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Recurring Event Series", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Backend generates occurrences across an 8-week rolling horizon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Series Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("WEEKLY", "BIWEEKLY", "MONTHLY").forEach { f ->
                        FilterChip(
                            selected = freq == f,
                            onClick = { freq = f },
                            label = { Text(f) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("TU", "TH", "FR", "SA", "SU").forEach { d ->
                        FilterChip(
                            selected = byDay == d,
                            onClick = { byDay = d },
                            label = { Text(d) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startsOn,
                        onValueChange = { startsOn = it },
                        label = { Text("Starts On (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text("Time (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        CreateSeriesRequest(
                            title = title.trim().ifBlank { "Group Meetup" },
                            durationMin = durationMin,
                            capacity = defaultCapacity,
                            recurrence = RecurrenceRequestDto(
                                freq = freq,
                                interval = if (freq == "BIWEEKLY") 2 else 1,
                                byday = if (freq == "MONTHLY") null else listOf(byDay),
                                startTime = startTime,
                                startsOn = startsOn
                            )
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = ForestPrimary, contentColor = SurfaceCream)
            ) {
                Text("Generate Series")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EventDetailScreen(
    event: EventDetailDto?,
    conflictErrorMessage: String?,
    pendingOutboxCount: Int,
    onSubmitRsvp: (String, Boolean) -> Unit,
    onOpenGroup: (String) -> Unit,
    onSubmitReview: (ReviewRequestDto) -> Unit
) {
    if (event == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ForestPrimary)
        }
        return
    }

    var showReviewModal by remember { mutableStateOf(false) }
    var simulateOfflineOutbox by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("event_detail_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!conflictErrorMessage.isNullOrBlank()) {
            item {
                AlertBanner(
                    message = conflictErrorMessage,
                    tone = BannerTone.ERROR,
                    modifier = Modifier.testTag("rsvp_409_conflict_banner")
                )
            }
        }

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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(text = event.status, tone = PillTone.FOREST)
                        TextButton(onClick = { onOpenGroup(event.group.groupId) }) {
                            Text(
                                text = "${event.group.name} →",
                                color = TerracottaBrand,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkDark
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Event time",
                            tint = TerracottaBrand,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${formatIsoDateTime(event.startTime)} – ${event.endTime.takeLast(9).take(5)}",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = InkDark
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = "Event location",
                            tint = ForestPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = event.location.address ?: "Berlin (${event.location.lat}, ${event.location.lng})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )
                    }

                    CapacityMeter(
                        confirmedCount = event.confirmedCount,
                        capacity = event.capacity
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusPill(text = "Going: ${event.rsvpCounts.going}", tone = PillTone.FOREST)
                        StatusPill(text = "Maybe: ${event.rsvpCounts.maybe}", tone = PillTone.GOLD)
                        StatusPill(
                            text = "Not Going: ${event.rsvpCounts.notGoing + event.rsvpCounts.declined}",
                            tone = PillTone.MUTED
                        )
                    }
                }
            }
        }

        // RSVP Card (Going / Maybe / Not Going + Room Outbox offline toggle)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Your RSVP",
                            style = MaterialTheme.typography.titleLarge,
                            color = InkDark
                        )
                        if (event.myStatus != null) {
                            StatusPill(
                                text = "Current: ${event.myStatus}",
                                tone = when (event.myStatus) {
                                    "GOING" -> PillTone.FOREST
                                    "MAYBE" -> PillTone.GOLD
                                    else -> PillTone.MUTED
                                }
                            )
                        }
                    }

                    Text(
                        text = "Going seats are reserved atomically in PostgreSQL. If all seats fill up concurrently, the server returns a 409 EVENT_FULL capacity conflict.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isGoing = event.myStatus == "GOING"
                        val isMaybe = event.myStatus == "MAYBE"
                        val isNotGoing = event.myStatus == "NOT_GOING" || event.myStatus == "DECLINED"

                        Button(
                            onClick = { onSubmitRsvp("GOING", simulateOfflineOutbox) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGoing) ForestPrimary else TerracottaBrand,
                                contentColor = SurfaceCream
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("rsvp_going_button")
                        ) {
                            Text("Going")
                        }

                        OutlinedButton(
                            onClick = { onSubmitRsvp("MAYBE", simulateOfflineOutbox) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isMaybe) TerracottaSoft else SurfaceCream,
                                contentColor = InkDark
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("rsvp_maybe_button")
                        ) {
                            Text("Maybe")
                        }

                        OutlinedButton(
                            onClick = { onSubmitRsvp("NOT_GOING", simulateOfflineOutbox) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isNotGoing) PaperCanvas else SurfaceCream,
                                contentColor = MutedText
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("rsvp_not_going_button")
                        ) {
                            Text("Not Going")
                        }
                    }

                    HorizontalDivider(color = LineBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Queue in Offline Room Outbox",
                                style = MaterialTheme.typography.labelLarge,
                                color = InkDark
                            )
                            Text(
                                text = "Ported from web/lib/outbox.ts (IndexedDB → Room + WorkManager). Currently queued: $pendingOutboxCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText
                            )
                        }
                        Switch(
                            checked = simulateOfflineOutbox,
                            onCheckedChange = { simulateOfflineOutbox = it },
                            modifier = Modifier.testTag("toggle_offline_outbox_switch")
                        )
                    }
                }
            }
        }

        // Post-occurrence Review card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, LineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Attended this meetup?",
                            style = MaterialTheme.typography.titleMedium,
                            color = InkDark
                        )
                        Text(
                            text = "Leave a rating and vibe tags to help future members.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText
                        )
                    }
                    OutlinedButton(
                        onClick = { showReviewModal = true },
                        modifier = Modifier.testTag("write_review_button")
                    ) {
                        Text("Write Review")
                    }
                }
            }
        }
    }

    if (showReviewModal) {
        var overall by remember { mutableIntStateOf(5) }
        var atmosphere by remember { mutableIntStateOf(5) }
        var organization by remember { mutableIntStateOf(5) }
        var feedback by remember { mutableStateOf("") }
        var selectedTags by remember { mutableStateOf(setOf("Welcoming", "Great conversation")) }
        val availableTags = listOf("Welcoming", "Great conversation", "On time", "Beginner friendly")

        AlertDialog(
            onDismissRequest = { showReviewModal = false },
            title = { Text("Review Meetup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Overall Rating: $overall / 5", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = overall.toFloat(),
                        onValueChange = { overall = it.toInt().coerceIn(1, 5) },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                    Text("Atmosphere: $atmosphere / 5 · Organization: $organization / 5", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = feedback,
                        onValueChange = { feedback = it },
                        label = { Text("How was the circle?") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        availableTags.take(2).forEach { tag ->
                            val selected = tag in selectedTags
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedTags = if (selected) selectedTags - tag else selectedTags + tag
                                },
                                label = { Text(tag) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReviewModal = false
                        onSubmitReview(
                            ReviewRequestDto(
                                overallRating = overall,
                                atmosphereRating = atmosphere,
                                organizationRating = organization,
                                feedback = feedback.trim().ifBlank { "Warm, welcoming meetup!" },
                                vibeTags = selectedTags.toList()
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForestPrimary, contentColor = SurfaceCream)
                ) {
                    Text("Publish Review")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReviewModal = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
