package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.data.repository.DemoSeedCatalog
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun LoginScreen(
    isSubmitting: Boolean,
    errorMessage: String?,
    onLogin: (String, String) -> Unit,
    onNavigateToRegister: () -> Unit,
    onDismissError: () -> Unit
) {
    var email by remember { mutableStateOf("demo@kinship.local") }
    var password by remember { mutableStateOf("Demo12345!") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        StatusPill(text = "WELCOME BACK", tone = PillTone.TERRACOTTA)
        Text(
            text = "Sign in to Kinship",
            style = MaterialTheme.typography.headlineLarge,
            color = InkDark
        )
        Text(
            text = "Pick up where your circle left off — upcoming meetups, group chat, and 48-hour match proposals.",
            style = MaterialTheme.typography.bodyLarge,
            color = MutedText
        )

        if (!errorMessage.isNullOrBlank()) {
            AlertBanner(
                message = errorMessage,
                tone = BannerTone.ERROR,
                onDismiss = onDismissError
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCream),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, LineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_email_input")
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_password_input")
                )

                Button(
                    onClick = { onLogin(email, password) },
                    enabled = !isSubmitting && email.isNotBlank() && password.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ForestPrimary,
                        contentColor = SurfaceCream
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("login_submit_button")
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            color = SurfaceCream,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text("Sign in", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        // Demo account helper card matching web/app/login/page.tsx
        Card(
            colors = CardDefaults.cardColors(containerColor = TerracottaSoft.copy(alpha = 0.65f)),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, TerracottaBrand.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Docker Compose Demo Account",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = InkDark
                    )
                    Text(
                        text = "demo@kinship.local · Demo12345!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedText
                    )
                }
                OutlinedButton(
                    onClick = {
                        email = "demo@kinship.local"
                        password = "Demo12345!"
                    },
                    border = BorderStroke(1.dp, TerracottaBrand),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TerracottaBrand),
                    modifier = Modifier.testTag("fill_demo_credentials_button")
                ) {
                    Text("Fill Demo")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("New to Kinship?", color = MutedText)
            TextButton(
                onClick = onNavigateToRegister,
                modifier = Modifier.testTag("navigate_register_button")
            ) {
                Text("Create an account", color = TerracottaBrand, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun RegisterScreen(
    isSubmitting: Boolean,
    errorMessage: String?,
    onRegister: (String, String, String) -> Unit,
    onNavigateToLogin: () -> Unit,
    onDismissError: () -> Unit
) {
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        StatusPill(text = "JOIN KINSHIP", tone = PillTone.FOREST)
        Text(
            text = "Create your account",
            style = MaterialTheme.typography.headlineLarge,
            color = InkDark
        )
        Text(
            text = "Meet 3–8 people around shared rituals in your neighbourhood. After registering, we'll guide you through a 3-step setup.",
            style = MaterialTheme.typography.bodyLarge,
            color = MutedText
        )

        if (!errorMessage.isNullOrBlank()) {
            AlertBanner(
                message = errorMessage,
                tone = BannerTone.ERROR,
                onDismiss = onDismissError
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCream),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, LineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name (2–80 chars)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_name_input")
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_email_input")
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (min 8 chars)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("register_password_input")
                )

                Button(
                    onClick = { onRegister(displayName, email, password) },
                    enabled = !isSubmitting && displayName.trim().length >= 2 && email.contains("@") && password.length >= 8,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TerracottaBrand,
                        contentColor = SurfaceCream
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("register_submit_button")
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            color = SurfaceCream,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text("Continue to 3-step onboarding", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Already have an account?", color = MutedText)
            TextButton(
                onClick = onNavigateToLogin,
                modifier = Modifier.testTag("navigate_login_button")
            ) {
                Text("Sign in", color = ForestPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Guided 3-step onboarding screen mirroring `web/app/onboarding/page.tsx`:
 * - Step 1: Display name, bio, profession, workplace + share toggle, neighbourhood location
 * - Step 2: Interests taxonomy (minimum 2 required for matching) + hobby tags
 * - Step 3: Weekly availability slots (day of week + time window)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    user: UserDto?,
    taxonomyInterests: List<InterestResponseDto>,
    onCompleteOnboarding: (UpdateProfileRequest) -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }
    var displayName by remember(user) { mutableStateOf(user?.displayName ?: "Mara Klein") }
    var bio by remember(user) { mutableStateOf(user?.bio ?: "Product designer who loves live jazz, bouldering, and slow Sunday coffee.") }
    var profession by remember(user) { mutableStateOf(user?.profession ?: "Product Designer") }
    var workplace by remember(user) { mutableStateOf(user?.workplace ?: "Studio Spree") }
    var workplaceShared by remember(user) { mutableStateOf(user?.workplaceShared ?: true) }
    var selectedPresetIndex by remember { mutableIntStateOf(0) }

    val selectedInterestIds = remember(user) {
        mutableStateListOf<Int>().apply {
            val initial = user?.interests?.map { it.interestId }.orEmpty()
            if (initial.isNotEmpty()) addAll(initial) else addAll(listOf(1, 2, 7))
        }
    }
    var hobbiesInput by remember(user) {
        mutableStateOf(
            user?.hobbies?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                ?: "Vinyl collecting, Espresso dialing, Film photography"
        )
    }

    val availabilitySlots = remember(user) {
        mutableStateListOf<AvailabilitySlotDto>().apply {
            val initial = user?.availability.orEmpty()
            if (initial.isNotEmpty()) {
                addAll(initial)
            } else {
                add(AvailabilitySlotDto(4, "18:00", "21:30"))
                add(AvailabilitySlotDto(6, "10:00", "14:00"))
            }
        }
    }

    var stepValidationError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(text = "STEP $currentStep OF 3", tone = PillTone.TERRACOTTA)
            Text(
                text = when (currentStep) {
                    1 -> "About & Location"
                    2 -> "Interests & Hobbies"
                    else -> "Weekly Availability"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MutedText
            )
        }

        LinearProgressIndicator(
            progress = { currentStep / 3f },
            color = TerracottaBrand,
            trackColor = LineBorder,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )

        if (stepValidationError != null) {
            AlertBanner(
                message = stepValidationError!!,
                tone = BannerTone.WARNING,
                onDismiss = { stepValidationError = null }
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceCream),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, LineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (currentStep) {
                    1 -> {
                        Text(
                            text = "Tell your future circle about yourself",
                            style = MaterialTheme.typography.headlineSmall,
                            color = InkDark
                        )
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("Display Name") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_name_input")
                        )
                        OutlinedTextField(
                            value = bio,
                            onValueChange = { bio = it },
                            label = { Text("Short bio (what brings you to small groups?)") },
                            minLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_bio_input")
                        )
                        OutlinedTextField(
                            value = profession,
                            onValueChange = { profession = it },
                            label = { Text("Profession") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = workplace,
                            onValueChange = { workplace = it },
                            label = { Text("Workplace / Studio (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Share workplace with group organizers",
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkDark
                            )
                            Switch(
                                checked = workplaceShared,
                                onCheckedChange = { workplaceShared = it }
                            )
                        }

                        HorizontalDivider(color = LineBorder)
                        Text(
                            text = "Home Neighbourhood (PostGIS anchor)",
                            style = MaterialTheme.typography.titleMedium,
                            color = InkDark
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DemoSeedCatalog.berlinPresets.forEachIndexed { idx, preset ->
                                FilterChip(
                                    selected = selectedPresetIndex == idx,
                                    onClick = { selectedPresetIndex = idx },
                                    label = { Text(preset.label) }
                                )
                            }
                        }
                    }

                    2 -> {
                        Text(
                            text = "Pick at least 2 interests",
                            style = MaterialTheme.typography.headlineSmall,
                            color = InkDark
                        )
                        Text(
                            text = "Kinship's matching engine uses Jaccard interest similarity (40% weight) to form 3–6 person groups.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            taxonomyInterests.forEach { item ->
                                val isSelected = item.id in selectedInterestIds
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (isSelected) selectedInterestIds.remove(item.id)
                                        else selectedInterestIds.add(item.id)
                                    },
                                    label = { Text("${item.name} (${item.category})") },
                                    leadingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null
                                )
                            }
                        }

                        OutlinedTextField(
                            value = hobbiesInput,
                            onValueChange = { hobbiesInput = it },
                            label = { Text("Specific hobbies (comma-separated)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    else -> {
                        Text(
                            text = "When are you free to meet?",
                            style = MaterialTheme.typography.headlineSmall,
                            color = InkDark
                        )
                        Text(
                            text = "Time-window overlap accounts for 30% of your match score.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText
                        )

                        val presetWindows = listOf(
                            Triple(2, "18:30", "21:30") to "Tue Evenings (18:30–21:30)",
                            Triple(4, "18:00", "21:30") to "Thu Evenings (18:00–21:30)",
                            Triple(5, "19:00", "22:30") to "Fri Evenings (19:00–22:30)",
                            Triple(6, "10:00", "14:00") to "Sat Mornings (10:00–14:00)",
                            Triple(7, "14:00", "18:00") to "Sun Afternoons (14:00–18:00)"
                        )

                        presetWindows.forEach { (slotTriple, label) ->
                            val dto = AvailabilitySlotDto(slotTriple.first, slotTriple.second, slotTriple.third)
                            val isSelected = availabilitySlots.any {
                                it.dayOfWeek == dto.dayOfWeek && it.startLocal == dto.startLocal
                            }
                            OutlinedCard(
                                onClick = {
                                    if (isSelected) {
                                        availabilitySlots.removeAll {
                                            it.dayOfWeek == dto.dayOfWeek && it.startLocal == dto.startLocal
                                        }
                                    } else {
                                        availabilitySlots.add(dto)
                                    }
                                },
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = if (isSelected) TerracottaSoft else SurfaceCream
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) TerracottaBrand else LineBorder
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = InkDark
                                    )
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = null
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (currentStep > 1) {
                        OutlinedButton(
                            onClick = { currentStep-- },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Back")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Button(
                        onClick = {
                            stepValidationError = null
                            if (currentStep == 1) {
                                if (displayName.trim().length < 2) {
                                    stepValidationError = "Please enter your display name."
                                    return@Button
                                }
                                currentStep = 2
                            } else if (currentStep == 2) {
                                if (selectedInterestIds.size < 2) {
                                    stepValidationError = "Select at least 2 interests so we can score matches."
                                    return@Button
                                }
                                currentStep = 3
                            } else {
                                if (availabilitySlots.isEmpty()) {
                                    stepValidationError = "Select at least one weekly availability window."
                                    return@Button
                                }
                                val preset = DemoSeedCatalog.berlinPresets[selectedPresetIndex]
                                val hobbies = hobbiesInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                onCompleteOnboarding(
                                    UpdateProfileRequest(
                                        displayName = displayName.trim(),
                                        bio = bio.trim(),
                                        profession = profession.trim(),
                                        workplace = workplace.trim(),
                                        workplaceShared = workplaceShared,
                                        location = LocationDto(preset.lat, preset.lng, preset.label),
                                        interests = selectedInterestIds.map { InterestSelectionDto(it, 1.0) },
                                        hobbies = hobbies,
                                        availability = availabilitySlots.toList(),
                                        onboardingCompleted = true
                                    )
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentStep == 3) TerracottaBrand else ForestPrimary,
                            contentColor = SurfaceCream
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("onboarding_next_button")
                    ) {
                        Text(if (currentStep < 3) "Next step" else "Finish setup & Explore")
                    }
                }
            }
        }
    }
}
