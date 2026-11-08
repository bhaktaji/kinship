package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ActivityCardDto
import com.example.data.model.ActivityDetailDto
import com.example.data.repository.DemoSeedCatalog
import com.example.ui.DiscoveryUiState
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: DiscoveryUiState,
    isOfflineCacheMode: Boolean,
    onUpdateCategory: (String) -> Unit,
    onUpdateRadius: (Int) -> Unit,
    onUpdateGroupSize: (Int?) -> Unit,
    onUpdateSearch: (String) -> Unit,
    onSelectManualArea: (String, Double, Double) -> Unit,
    onRequestGpsLocation: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenActivity: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenEvent: (String) -> Unit
) {
    var showLocationSheet by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            onRequestGpsLocation()
        }
    }

    val filteredItems = remember(state.items, state.searchQuery) {
        val q = state.searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            state.items
        } else {
            state.items.filter { item ->
                item.title.lowercase().contains(q) ||
                    item.groupName.lowercase().contains(q) ||
                    (item.description?.lowercase()?.contains(q) == true) ||
                    item.category.lowercase().contains(q)
            }
        }
    }

    val categories = listOf(
        "" to "All Categories",
        "music" to "Music",
        "sport" to "Sport",
        "outdoors" to "Outdoors",
        "food" to "Food & Drink",
        "books" to "Books & Ideas",
        "art" to "Art & Craft"
    )
    val radiusOptions = listOf(3, 5, 10, 25)
    val sizeOptions = listOf(null to "Any size", 4 to "≤ 4", 6 to "≤ 6", 8 to "≤ 8")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("discover_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(text = "POSTGIS RADIUS DISCOVERY", tone = PillTone.TERRACOTTA)
                        if (isOfflineCacheMode) {
                            StatusPill(text = "Room Cached / Seed", tone = PillTone.SAGE)
                        }
                    }

                    Text(
                        text = "Small circles, recurring rituals",
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkDark
                    )
                    Text(
                        text = "Every group is capped at 3–8 people so conversations stay warm and everyone gets to know each other.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )

                    // Location selector bar (FusedLocation GPS + Manual Berlin fallback)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showLocationSheet = true },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, LineBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("location_picker_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LocationOn,
                                contentDescription = "Selected area",
                                tint = TerracottaBrand,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${state.areaLabel} · ${state.radiusKm} km",
                                color = InkDark,
                                maxLines = 1
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = TerracottaSoft,
                                contentColor = TerracottaBrand
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("gps_location_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MyLocation,
                                contentDescription = "Use GPS location",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("GPS")
                        }
                    }

                    // Search box
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onUpdateSearch,
                        placeholder = { Text("Search music, bouldering, coffee, book clubs…") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = "Search activities"
                            )
                        },
                        trailingIcon = if (state.searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { onUpdateSearch("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("discover_search_input")
                    )

                    // Category chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.forEach { (key, label) ->
                            val selected = state.category.equals(key, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateCategory(key) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ForestPrimary,
                                    selectedLabelColor = SurfaceCream
                                ),
                                modifier = Modifier.testTag("category_chip_${key.ifEmpty { "all" }}")
                            )
                        }
                    }

                    // Radius & Max group size row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Radius:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedText
                        )
                        radiusOptions.forEach { km ->
                            FilterChip(
                                selected = state.radiusKm == km,
                                onClick = { onUpdateRadius(km) },
                                label = { Text("$km km") }
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Group size:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedText
                        )
                        sizeOptions.forEach { (maxSz, label) ->
                            FilterChip(
                                selected = state.groupSizeMax == maxSz,
                                onClick = { onUpdateGroupSize(maxSz) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        if (state.isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = ForestPrimary)
                }
            }
        } else if (filteredItems.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceCream),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, LineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "No circles match those filters",
                            style = MaterialTheme.typography.titleLarge,
                            color = InkDark
                        )
                        Text(
                            text = "Try expanding your radius to 25 km or switching back to Central Berlin.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )
                        OutlinedButton(
                            onClick = {
                                onUpdateCategory("")
                                onUpdateRadius(25)
                                onUpdateGroupSize(null)
                                onUpdateSearch("")
                            }
                        ) {
                            Text("Reset filters")
                        }
                    }
                }
            }
        } else {
            items(filteredItems, key = { "${it.activityId}_${it.groupId}" }) { item ->
                ActivityDiscoveryCard(
                    item = item,
                    onOpenActivity = { onOpenActivity(item.activityId) },
                    onOpenGroup = { onOpenGroup(item.groupId) },
                    onOpenEvent = { onOpenEvent(item.nextEvent.eventId) }
                )
            }

            if (state.nextPageToken != null) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.isLoadingMore,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("discover_load_more_button")
                    ) {
                        if (state.isLoadingMore) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text("Load more activities")
                        }
                    }
                }
            }
        }
    }

    if (showLocationSheet) {
        AlertDialog(
            onDismissRequest = { showLocationSheet = false },
            title = {
                Text(
                    text = "Choose discovery neighbourhood",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Select a Berlin neighbourhood or use your phone's GPS coordinates:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText
                    )
                    DemoSeedCatalog.berlinPresets.forEach { preset ->
                        OutlinedCard(
                            onClick = {
                                onSelectManualArea(preset.label, preset.lat, preset.lng)
                                showLocationSheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "${"%.3f".format(preset.lat)}, ${"%.3f".format(preset.lng)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MutedText
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLocationSheet = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ActivityDiscoveryCard(
    item: ActivityCardDto,
    onOpenActivity: () -> Unit,
    onOpenGroup: () -> Unit,
    onOpenEvent: () -> Unit
) {
    Card(
        onClick = onOpenActivity,
        colors = CardDefaults.cardColors(containerColor = SurfaceCream),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, LineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity_card_${item.activityId}")
    ) {
        Column {
            CategoryArtworkBanner(
                category = item.category,
                trailingBadge = formatDistanceLabel(item.distanceM)
            )

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = InkDark
                )

                if (!item.description.isNullOrBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedText,
                        maxLines = 2
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = item.groupName,
                        tone = PillTone.FOREST
                    )
                    Text(
                        text = "Next: ${formatIsoDateTime(item.nextEvent.startTime)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TerracottaBrand
                    )
                }

                CapacityMeter(
                    confirmedCount = item.memberCount,
                    capacity = item.maxMembers
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenGroup,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("open_group_button_${item.groupId}")
                    ) {
                        Text("Circle Details")
                    }
                    Button(
                        onClick = onOpenEvent,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ForestPrimary,
                            contentColor = SurfaceCream
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("open_event_rsvp_button_${item.nextEvent.eventId}")
                    ) {
                        Text("Next Meetup RSVP")
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityDetailScreen(
    activity: ActivityDetailDto?,
    onOpenGroup: (String) -> Unit,
    onOpenEvent: (String) -> Unit
) {
    if (activity == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ForestPrimary)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                        category = activity.category,
                        height = 164.dp,
                        trailingBadge = activity.organization?.name
                    )
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = activity.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = InkDark
                        )
                        if (!activity.description.isNullOrBlank()) {
                            Text(
                                text = activity.description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MutedText
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = "Activity location",
                                tint = TerracottaBrand,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = activity.location.address
                                    ?: "Berlin (${"%.4f".format(activity.location.lat)}, ${"%.4f".format(activity.location.lng)})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkDark
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Active Circles Hosting This Ritual (${activity.groups.size})",
                style = MaterialTheme.typography.titleLarge,
                color = InkDark
            )
        }

        items(activity.groups, key = { it.groupId }) { group ->
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
                            text = group.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = InkDark
                        )
                        StatusPill(
                            text = group.visibility.uppercase(),
                            tone = if (group.visibility == "private") PillTone.GOLD else PillTone.FOREST
                        )
                    }

                    CapacityMeter(
                        confirmedCount = group.memberCount,
                        capacity = group.maxMembers
                    )

                    if (group.nextEvent != null) {
                        Text(
                            text = "Next occurrence: ${formatIsoDateTime(group.nextEvent.startTime)}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TerracottaBrand
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onOpenGroup(group.groupId) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("View Group")
                        }
                        if (group.nextEvent != null) {
                            Button(
                                onClick = { onOpenEvent(group.nextEvent.eventId) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ForestPrimary,
                                    contentColor = SurfaceCream
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("RSVP to Event")
                            }
                        }
                    }
                }
            }
        }
    }
}
