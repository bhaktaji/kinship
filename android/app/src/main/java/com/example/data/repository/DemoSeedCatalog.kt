package com.example.data.repository

import com.example.data.model.*

/**
 * Provides deterministic Berlin pilot seed data matching `DemoDataSeeder.java` (`README.md` §Database,
 * Migrations & Seed Data) ONLY when the local Docker Compose backend (`10.0.2.2:8080`) is unreachable
 * in an isolated preview environment. When the real Spring Boot backend is reachable, all repositories
 * use real HTTP/WebSocket responses directly.
 */
data class BerlinPreset(
    val label: String,
    val lat: Double,
    val lng: Double
)

object DemoSeedCatalog {

    val berlinPresets = listOf(
        BerlinPreset("Central Berlin", 52.5200, 13.4050),
        BerlinPreset("Kreuzberg", 52.4986, 13.4033),
        BerlinPreset("Neukölln", 52.4811, 13.4351),
        BerlinPreset("Prenzlauer Berg", 52.5388, 13.4244),
        BerlinPreset("Friedrichshain", 52.5158, 13.4543),
        BerlinPreset("Charlottenburg", 52.5162, 13.3042)
    )

    val demoUser = UserDto(
        id = "00000000-0000-4000-8000-000000000001",
        email = "demo@kinship.local",
        displayName = "Mara Klein",
        timezone = "Europe/Berlin",
        location = LocationDto(lat = 52.5200, lng = 13.4050, address = "Mitte, Berlin"),
        availability = listOf(
            AvailabilitySlotDto(dayOfWeek = 4, startLocal = "18:00", endLocal = "21:00")
        ),
        interests = listOf(
            UserInterestDto(interestId = 1, slug = "live-music", name = "Live Music", weight = 1.0),
            UserInterestDto(interestId = 2, slug = "bouldering", name = "Bouldering", weight = 1.0),
            UserInterestDto(interestId = 3, slug = "specialty-coffee", name = "Specialty Coffee", weight = 1.0)
        ),
        createdAt = "2026-11-01T10:00:00Z",
        bio = "Product designer in Berlin who loves acoustic gigs, Thursday bouldering, and neighborhood book swaps.",
        hobbies = listOf("Vinyl hunting", "Bouldering", "Sourdough"),
        profession = "Product Designer",
        workplace = "Studio Spree",
        workplaceShared = true,
        onboardingCompleted = true,
        onboardingStep = 3
    )

    val taxonomyInterests = listOf(
        InterestResponseDto(1, "live-music", "Live Music", "music"),
        InterestResponseDto(2, "bouldering", "Bouldering", "sport"),
        InterestResponseDto(3, "running", "Canal Running", "sport"),
        InterestResponseDto(4, "hiking", "Brandenburg Trails", "outdoors"),
        InterestResponseDto(5, "board-games", "Strategy Board Games", "books"),
        InterestResponseDto(6, "book-club", "Indie Book Club", "books"),
        InterestResponseDto(7, "supper-club", "Neighborhood Supper Club", "food"),
        InterestResponseDto(8, "ceramics", "Wheel Ceramics", "art"),
        InterestResponseDto(9, "jazz-jam", "Acoustic & Jazz Jam", "music"),
        InterestResponseDto(10, "urban-sketching", "Urban Sketching", "art")
    )

    val seededActivities = listOf(
        ActivityCardDto(
            activityId = "10000000-0000-4000-8000-000000000001",
            title = "Live Music Night #2",
            category = "music",
            description = "Intimate weekly acoustic & indie sessions at Donau115 followed by a quiet drink.",
            location = LocationDto(52.4810, 13.4350, "Donau115, Neukölln, Berlin"),
            distanceM = 640,
            groupId = "20000000-0000-4000-8000-000000000001",
            groupName = "Live Music Night #2 Crew",
            memberCount = 5,
            maxMembers = 6,
            nextEvent = NextEventDto(
                eventId = "30000000-0000-4000-8000-000000000001",
                startTime = "2026-11-12T18:30:00Z"
            )
        ),
        ActivityCardDto(
            activityId = "10000000-0000-4000-8000-000000000002",
            title = "Thursday Bouldering Session",
            category = "sport",
            description = "All-levels bouldering circuit at Ostbloc with coffee & stretching afterwards.",
            location = LocationDto(52.4912, 13.4910, "Ostbloc, Rummelsburg, Berlin"),
            distanceM = 1180,
            groupId = "20000000-0000-4000-8000-000000000002",
            groupName = "Thursday Bouldering Crew",
            memberCount = 4,
            maxMembers = 6,
            nextEvent = NextEventDto(
                eventId = "30000000-0000-4000-8000-000000000002",
                startTime = "2026-11-12T19:00:00Z"
            )
        ),
        ActivityCardDto(
            activityId = "10000000-0000-4000-8000-000000000003",
            title = "Landwehr Canal 5K Run & Pastry",
            category = "outdoors",
            description = "Conversational pace 5 km loop along the Landwehr Canal ending at a local bakery.",
            location = LocationDto(52.4965, 13.4215, "Maybachufer, Kreuzberg, Berlin"),
            distanceM = 1420,
            groupId = "20000000-0000-4000-8000-000000000003",
            groupName = "Saturday Canal Runners",
            memberCount = 5,
            maxMembers = 6,
            nextEvent = NextEventDto(
                eventId = "30000000-0000-4000-8000-000000000003",
                startTime = "2026-11-14T09:30:00Z"
            )
        ),
        ActivityCardDto(
            activityId = "10000000-0000-4000-8000-000000000004",
            title = "Sunday Family-Style Supper Table",
            category = "food",
            description = "Each circle member brings one seasonal dish; we rotate neighborhood kitchens and courtyards.",
            location = LocationDto(52.5388, 13.4244, "Kollwitzplatz, Prenzlauer Berg, Berlin"),
            distanceM = 2150,
            groupId = "20000000-0000-4000-8000-000000000004",
            groupName = "Kollwitz Supper Circle",
            memberCount = 4,
            maxMembers = 6,
            nextEvent = NextEventDto(
                eventId = "30000000-0000-4000-8000-000000000004",
                startTime = "2026-11-15T18:00:00Z"
            )
        ),
        ActivityCardDto(
            activityId = "10000000-0000-4000-8000-000000000005",
            title = "Short Fiction & Essay Circle",
            category = "books",
            description = "One short story or essay per week — zero homework pressure, thoughtful conversation.",
            location = LocationDto(52.5162, 13.3042, "Savignyplatz, Charlottenburg, Berlin"),
            distanceM = 3400,
            groupId = "20000000-0000-4000-8000-000000000005",
            groupName = "Savigny Readers Table",
            memberCount = 5,
            maxMembers = 6,
            nextEvent = NextEventDto(
                eventId = "30000000-0000-4000-8000-000000000005",
                startTime = "2026-11-11T19:00:00Z"
            )
        ),
        ActivityCardDto(
            activityId = "10000000-0000-4000-8000-000000000006",
            title = "Museum Courtyard Sketchbook Hour",
            category = "art",
            description = "Bring a pencil or watercolor tin; we sketch architecture and swap notes over tea.",
            location = LocationDto(52.5211, 13.3969, "Museumsinsel, Mitte, Berlin"),
            distanceM = 890,
            groupId = "20000000-0000-4000-8000-000000000006",
            groupName = "Mitte Sketchbook Crew",
            memberCount = 3,
            maxMembers = 5,
            nextEvent = NextEventDto(
                eventId = "30000000-0000-4000-8000-000000000006",
                startTime = "2026-11-15T14:00:00Z"
            )
        )
    )
}
