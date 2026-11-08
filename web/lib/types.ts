export interface ActivityLocation {
  lat: number;
  lng: number;
  address?: string;
}

export interface NextEvent {
  event_id: string;
  start_time: string;
}

export interface ActivityCard {
  activity_id: string;
  title: string;
  category: string;
  description?: string;
  image_url?: string;
  location: ActivityLocation;
  distance_m: number;
  group_id: string;
  group_name: string;
  member_count: number;
  max_members: number;
  next_event: NextEvent;
}

export interface DiscoveryPage {
  items: ActivityCard[];
  next_page_token?: string;
}

export interface GroupSummary {
  group_id: string;
  name: string;
  visibility: string;
  member_count: number;
  max_members: number;
  next_event?: NextEvent;
}

export interface ActivityDetail {
  activity_id: string;
  title: string;
  description?: string;
  category: string;
  image_url?: string;
  location: ActivityLocation;
  organization?: { id: string; name: string; type?: string };
  groups: GroupSummary[];
}

export interface UserInterest {
  interest_id: number;
  slug: string;
  name: string;
  weight: number;
}

export interface InterestResponse {
  id: number;
  slug: string;
  name: string;
  category: string;
}

export interface InterestsResponse {
  items: InterestResponse[];
}

export interface User {
  id: string;
  email: string;
  display_name: string;
  timezone: string;
  location?: { lat: number; lng: number };
  availability: { day_of_week: number; start_local: string; end_local: string }[];
  interests: UserInterest[];
  created_at: string;
  bio?: string;
  hobbies?: string[];
  profession?: string;
  workplace?: string;
  workplace_shared?: boolean;
  profile_photo_asset_id?: string;
  onboarding_completed?: boolean;
  onboarding_step?: number;
}

export interface AuthResponse {
  user: User;
  access_token: string;
  token_type: string;
  expires_in: number;
}

export interface TokenResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
}

/* ------------------------------------------------------------------ */
/* P2 groups and events                                                */
/* ------------------------------------------------------------------ */

export interface Group {
  group_id: string;
  name: string;
  description?: string;
  category: string;
  activity_id?: string;
  location: ActivityLocation;
  max_members: number;
  visibility: "public" | "private";
  join_policy?: "OPEN" | "APPROVAL";
  status: "ACTIVE" | "ARCHIVED";
  created_by: string;
  created_at: string;
  active_member_count: number;
  my_role?: "OWNER" | "MEMBER" | null;
  my_status?: "ACTIVE" | "PENDING" | null;
}

export interface GroupMember {
  user_id: string;
  display_name: string;
  role: "OWNER" | "MEMBER";
  status: "ACTIVE" | "PENDING";
  joined_at: string;
  profile_photo_asset_id?: string;
}

export interface EventSummary {
  event_id: string;
  series_id?: string;
  title: string;
  start_time: string;
  end_time: string;
  capacity: number;
  confirmed_count: number;
  status: "SCHEDULED" | "CANCELLED";
  my_status?: string | null;
}

export interface GroupDetail {
  group: Group;
  members: GroupMember[];
  next_events: EventSummary[];
}

export interface GroupCard {
  group_id: string;
  name: string;
  category: string;
  visibility: string;
  max_members: number;
  member_count: number;
  my_status: string;
  next_event_id?: string;
  next_event_at?: string;
}

export interface GroupPage {
  items: GroupCard[];
  next_page_token?: string;
}

export interface RsvpCounts {
  going: number;
  maybe: number;
  declined: number;
  not_going: number;
}

export interface EventDetail {
  event_id: string;
  series_id?: string;
  title: string;
  start_time: string;
  end_time: string;
  capacity: number;
  confirmed_count: number;
  status: "SCHEDULED" | "CANCELLED";
  my_status?: string | null;
  rsvp_counts: RsvpCounts;
  group: { group_id: string; name: string; visibility: string };
  location: ActivityLocation;
}

export interface RsvpResponse {
  event_id: string;
  status: string;
  confirmed_count: number;
  capacity: number;
}

/* ------------------------------------------------------------------ */
/* P3 matching                                                         */
/* ------------------------------------------------------------------ */

export interface MemberPreview {
  user_id: string;
  display_name: string;
  interests: string[];
  distance_km: number;
}

export interface MatchInvitation {
  invitation_id: string;
  match_group_id: string;
  expires_at: string;
  status: string;
  members: MemberPreview[];
}

export interface InvitationPage {
  items: MatchInvitation[];
}

export interface MatchResult {
  match_group_id: string;
  status: string;
  score: number;
  expires_at: string;
  members: MemberPreview[];
}

export interface MatchJob {
  job_id: string;
  status: "QUEUED" | "RUNNING" | "DONE" | "FAILED";
  error_code?: string;
  results: MatchResult[];
}

export interface MatchJobCreated {
  job_id: string;
  status: string;
}

/* ------------------------------------------------------------------ */
/* P4 messaging                                                        */
/* ------------------------------------------------------------------ */

export interface ChatMessage {
  message_id: string;
  sequence_id: number;
  sender_id: string;
  body: string;
  created_at: string;
}

export interface MessagePage {
  items: ChatMessage[];
  next_before_seq?: number | null;
}

export interface SendResponse {
  message_id: string;
  sequence_id: number;
  created_at: string;
}

export interface RealtimeTicket {
  ticket: string;
  expires_in: number;
}

export interface Notification {
  id: string;
  type: string;
  title: string;
  body?: string;
  entity_type?: string;
  entity_id?: string;
  read_at?: string;
  created_at: string;
}

export interface NotificationPage {
  items: Notification[];
  unread_count: number;
  next_page_token?: string;
}

export interface JoinRequestView {
  request_id: string;
  group_id: string;
  user_id: string;
  reason: string;
  share_workplace: boolean;
  display_name: string;
  bio?: string;
  hobbies?: string[];
  profession?: string;
  workplace?: string;
  profile_photo_asset_id?: string;
  availability?: { day_of_week: number; start_local: string; end_local: string }[];
  created_at: string;
}

export interface ReviewView {
  id: string;
  occurrence_id: string;
  user_id: string;
  overall_rating: number;
  atmosphere_rating: number;
  organization_rating: number;
  feedback?: string;
  vibe_tags?: string[];
  created_at: string;
  updated_at: string;
  edited_until: string;
}

export interface ReviewSummary {
  count: number;
  avg_overall?: number | null;
  avg_atmosphere?: number | null;
  avg_organization?: number | null;
  recent: ReviewView[];
}
