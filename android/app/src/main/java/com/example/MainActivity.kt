package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.ui.KinshipViewModel
import com.example.ui.components.*
import com.example.ui.screens.*
import com.example.ui.theme.KinshipTheme
import com.example.ui.theme.PaperCanvas
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KinshipTheme {
                KinshipApp()
            }
        }
    }
}

@Composable
fun KinshipApp(
    viewModel: KinshipViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: MainTab.DISCOVER.route

    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val authSubmitting by viewModel.authSubmitting.collectAsStateWithLifecycle()
    val authError by viewModel.authError.collectAsStateWithLifecycle()
    val isOfflineOrPreview by viewModel.isOfflineOrPreview.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadNotificationCount.collectAsStateWithLifecycle()
    val pendingOutbox by viewModel.pendingOutboxItems.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    val taxonomyInterests by viewModel.taxonomyInterests.collectAsStateWithLifecycle()
    val discoveryState by viewModel.discoveryState.collectAsStateWithLifecycle()
    val selectedActivity by viewModel.selectedActivity.collectAsStateWithLifecycle()
    val myGroups by viewModel.myGroups.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val selectedGroupReviews by viewModel.selectedGroupReviews.collectAsStateWithLifecycle()
    val selectedEvent by viewModel.selectedEvent.collectAsStateWithLifecycle()
    val eventConflictError by viewModel.eventConflictError.collectAsStateWithLifecycle()
    val matchInvitations by viewModel.matchInvitations.collectAsStateWithLifecycle()
    val latestMatchJob by viewModel.latestMatchJob.collectAsStateWithLifecycle()
    val matchesLoading by viewModel.matchesLoading.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()

    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val chatWsConnected by viewModel.chatWsConnected.collectAsStateWithLifecycle()
    val chatNextBeforeSeq by viewModel.chatNextBeforeSeq.collectAsStateWithLifecycle()

    val activeTab = MainTab.entries.find { it.route == currentRoute }
    val isSubScreen = activeTab == null && currentRoute !in setOf("login", "register")

    val subScreenTitle = when {
        currentRoute.startsWith("activity/") -> selectedActivity?.title ?: "Activity"
        currentRoute.endsWith("/chat") -> selectedGroup?.group?.name ?: "Group Chat"
        currentRoute.startsWith("group/") -> selectedGroup?.group?.name ?: "Group Details"
        currentRoute.startsWith("event/") -> selectedEvent?.title ?: "Event RSVP"
        currentRoute == "notifications" -> "Notifications"
        currentRoute == "onboarding" -> "3-Step Onboarding"
        else -> null
    }

    Scaffold(
        containerColor = PaperCanvas,
        topBar = {
            if (currentRoute !in setOf("login", "register")) {
                AppTopHeader(
                    unreadCount = unreadCount,
                    pendingOutboxCount = pendingOutbox.size,
                    onOpenNotifications = {
                        viewModel.loadNotifications()
                        navController.navigate("notifications") {
                            launchSingleTop = true
                        }
                    },
                    onOpenProfile = {
                        navController.navigate(MainTab.PROFILE.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    userDisplayName = currentUser?.displayName,
                    backTitle = if (isSubScreen) subScreenTitle else null,
                    onBack = if (isSubScreen) {
                        {
                            if (currentRoute.endsWith("/chat")) {
                                viewModel.closeGroupChat()
                            }
                            navController.popBackStack()
                        }
                    } else null
                )
            }
        },
        bottomBar = {
            if (currentRoute !in setOf("login", "register", "onboarding") && !currentRoute.endsWith("/chat")) {
                KinshipBottomNavBar(
                    currentTab = activeTab,
                    onSelectTab = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (bannerMessage != null) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AlertBanner(
                        message = bannerMessage!!.text,
                        tone = bannerMessage!!.tone,
                        onDismiss = { viewModel.clearBanner() }
                    )
                }
            }

            NavHost(
                navController = navController,
                startDestination = MainTab.DISCOVER.route,
                modifier = Modifier.weight(1f)
            ) {
                composable("login") {
                    LoginScreen(
                        isSubmitting = authSubmitting,
                        errorMessage = authError,
                        onLogin = { email, password ->
                            viewModel.login(email, password) { user ->
                                val dest = if (user.onboardingCompleted) MainTab.DISCOVER.route else "onboarding"
                                navController.navigate(dest) {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        },
                        onNavigateToRegister = {
                            viewModel.clearAuthError()
                            navController.navigate("register")
                        },
                        onDismissError = { viewModel.clearAuthError() }
                    )
                }

                composable("register") {
                    RegisterScreen(
                        isSubmitting = authSubmitting,
                        errorMessage = authError,
                        onRegister = { name, email, password ->
                            viewModel.register(name, email, password) {
                                navController.navigate("onboarding") {
                                    popUpTo("register") { inclusive = true }
                                }
                            }
                        },
                        onNavigateToLogin = {
                            viewModel.clearAuthError()
                            navController.popBackStack()
                        },
                        onDismissError = { viewModel.clearAuthError() }
                    )
                }

                composable("onboarding") {
                    OnboardingScreen(
                        user = currentUser,
                        taxonomyInterests = taxonomyInterests,
                        onCompleteOnboarding = { req ->
                            viewModel.saveOnboardingOrProfile(
                                request = req,
                                successMessage = "Onboarding complete — welcome to Kinship!"
                            ) {
                                navController.navigate(MainTab.DISCOVER.route) {
                                    popUpTo("onboarding") { inclusive = true }
                                }
                            }
                        }
                    )
                }

                composable(MainTab.DISCOVER.route) {
                    DiscoverScreen(
                        state = discoveryState,
                        isOfflineCacheMode = isOfflineOrPreview,
                        onUpdateCategory = { cat -> viewModel.updateDiscoveryFilters(category = cat) },
                        onUpdateRadius = { km -> viewModel.updateDiscoveryFilters(radiusKm = km) },
                        onUpdateGroupSize = { sz -> viewModel.updateDiscoveryFilters(groupSizeMax = sz) },
                        onUpdateSearch = { q -> viewModel.updateDiscoveryFilters(searchQuery = q) },
                        onSelectManualArea = { label, lat, lng -> viewModel.selectManualArea(label, lat, lng) },
                        onRequestGpsLocation = { viewModel.useDeviceGpsLocation() },
                        onLoadMore = { viewModel.loadMoreDiscovery() },
                        onOpenActivity = { activityId ->
                            viewModel.loadActivityDetail(activityId)
                            navController.navigate("activity/$activityId")
                        },
                        onOpenGroup = { groupId ->
                            viewModel.loadGroupDetail(groupId)
                            navController.navigate("group/$groupId")
                        },
                        onOpenEvent = { eventId ->
                            viewModel.loadEventDetail(eventId)
                            navController.navigate("event/$eventId")
                        }
                    )
                }

                composable(
                    route = "activity/{activityId}",
                    arguments = listOf(navArgument("activityId") { type = NavType.StringType })
                ) { entry ->
                    val activityId = entry.arguments?.getString("activityId").orEmpty()
                    LaunchedEffect(activityId) {
                        viewModel.loadActivityDetail(activityId)
                    }
                    ActivityDetailScreen(
                        activity = selectedActivity,
                        onOpenGroup = { groupId ->
                            viewModel.loadGroupDetail(groupId)
                            navController.navigate("group/$groupId")
                        },
                        onOpenEvent = { eventId ->
                            viewModel.loadEventDetail(eventId)
                            navController.navigate("event/$eventId")
                        }
                    )
                }

                composable(MainTab.GROUPS.route) {
                    LaunchedEffect(Unit) {
                        viewModel.loadMyGroups()
                    }
                    GroupsListScreen(
                        groups = myGroups,
                        onOpenGroup = { groupId ->
                            viewModel.loadGroupDetail(groupId)
                            navController.navigate("group/$groupId")
                        },
                        onOpenChat = { groupId ->
                            viewModel.openGroupChat(groupId)
                            navController.navigate("group/$groupId/chat")
                        },
                        onOpenEvent = { eventId ->
                            viewModel.loadEventDetail(eventId)
                            navController.navigate("event/$eventId")
                        },
                        onCreateGroup = { req ->
                            viewModel.createGroup(req) { createdGroupId ->
                                navController.navigate("group/$createdGroupId")
                            }
                        }
                    )
                }

                composable(
                    route = "group/{groupId}",
                    arguments = listOf(navArgument("groupId") { type = NavType.StringType })
                ) { entry ->
                    val groupId = entry.arguments?.getString("groupId").orEmpty()
                    LaunchedEffect(groupId) {
                        viewModel.loadGroupDetail(groupId)
                    }
                    GroupDetailScreen(
                        detail = selectedGroup,
                        reviews = selectedGroupReviews,
                        onJoinGroup = { gId -> viewModel.joinGroup(gId) },
                        onRequestJoinApproval = { gId, reason, shareWp ->
                            viewModel.requestJoinApproval(gId, reason, shareWp)
                        },
                        onLeaveGroup = { gId -> viewModel.leaveGroup(gId) },
                        onUpdateMemberStatus = { gId, uId, status ->
                            viewModel.updateMemberStatus(gId, uId, status)
                        },
                        onCreateSeries = { gId, req ->
                            viewModel.createRecurringSeries(gId, req) {}
                        },
                        onOpenChat = { gId ->
                            viewModel.openGroupChat(gId)
                            navController.navigate("group/$gId/chat")
                        },
                        onOpenEvent = { evId ->
                            viewModel.loadEventDetail(evId)
                            navController.navigate("event/$evId")
                        },
                        onReportReview = { revId, reason ->
                            viewModel.reportReview(revId, reason)
                        }
                    )
                }

                composable(
                    route = "group/{groupId}/chat",
                    arguments = listOf(navArgument("groupId") { type = NavType.StringType })
                ) { entry ->
                    val groupId = entry.arguments?.getString("groupId").orEmpty()
                    DisposableEffect(groupId) {
                        viewModel.openGroupChat(groupId)
                        onDispose {
                            viewModel.closeGroupChat()
                        }
                    }
                    GroupChatScreen(
                        groupDetail = selectedGroup,
                        currentUserId = currentUser?.id,
                        messages = chatMessages,
                        isWsConnected = chatWsConnected,
                        hasOlderMessages = chatNextBeforeSeq != null,
                        onLoadOlder = { viewModel.loadOlderChatMessages() },
                        onSendMessage = { body -> viewModel.sendChatMessage(body) }
                    )
                }

                composable(
                    route = "event/{eventId}",
                    arguments = listOf(navArgument("eventId") { type = NavType.StringType })
                ) { entry ->
                    val eventId = entry.arguments?.getString("eventId").orEmpty()
                    LaunchedEffect(eventId) {
                        viewModel.loadEventDetail(eventId)
                    }
                    EventDetailScreen(
                        event = selectedEvent,
                        conflictErrorMessage = eventConflictError,
                        pendingOutboxCount = pendingOutbox.size,
                        onSubmitRsvp = { status, forceOffline ->
                            viewModel.submitRsvp(eventId, status, forceOffline)
                        },
                        onOpenGroup = { gId ->
                            viewModel.loadGroupDetail(gId)
                            navController.navigate("group/$gId")
                        },
                        onSubmitReview = { reviewReq ->
                            val gId = selectedEvent?.group?.groupId ?: return@EventDetailScreen
                            viewModel.submitOccurrenceReview(eventId, gId, reviewReq) {}
                        }
                    )
                }

                composable(MainTab.MATCHES.route) {
                    LaunchedEffect(Unit) {
                        viewModel.loadMatches()
                    }
                    MatchesScreen(
                        invitations = matchInvitations,
                        latestJob = latestMatchJob,
                        isLoading = matchesLoading,
                        onRequestMatch = { distKm, prefSize ->
                            viewModel.requestNewMatch(distKm, prefSize)
                        },
                        onRespondInvitation = { invId, accept ->
                            viewModel.respondToMatchInvitation(invId, accept) { newGroupId ->
                                viewModel.loadGroupDetail(newGroupId)
                                navController.navigate("group/$newGroupId")
                            }
                        }
                    )
                }

                composable("notifications") {
                    LaunchedEffect(Unit) {
                        viewModel.loadNotifications()
                    }
                    NotificationsScreen(
                        notifications = notifications,
                        unreadCount = unreadCount,
                        onMarkRead = { id -> viewModel.markNotificationRead(id) },
                        onMarkAllRead = { viewModel.markAllNotificationsRead() },
                        onNavigateEntity = { entityType, entityId ->
                            if (entityId != null) {
                                when (entityType) {
                                    "GROUP" -> {
                                        viewModel.loadGroupDetail(entityId)
                                        navController.navigate("group/$entityId")
                                    }
                                    "EVENT" -> {
                                        viewModel.loadEventDetail(entityId)
                                        navController.navigate("event/$entityId")
                                    }
                                    "MATCH" -> {
                                        navController.navigate(MainTab.MATCHES.route)
                                    }
                                }
                            }
                        }
                    )
                }

                composable(MainTab.PROFILE.route) {
                    ProfileScreen(
                        user = currentUser,
                        taxonomyInterests = taxonomyInterests,
                        onSaveProfile = { req ->
                            viewModel.saveOnboardingOrProfile(req, "Profile updated!")
                        },
                        onUploadPhoto = { uri ->
                            viewModel.uploadProfilePhoto(uri)
                        },
                        onRestartOnboarding = {
                            navController.navigate("onboarding")
                        },
                        onLogout = {
                            viewModel.logout {
                                navController.navigate("login") {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
