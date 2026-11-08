# Kinship Project Memory

## Stack
- Backend: Spring Boot 3.3, Java 21, PostgreSQL 16 + PostGIS, Redis 7, Flyway.
- Frontend: Next.js 15, TypeScript, Tailwind, TanStack Query.
- Local run: Docker Compose for postgres/redis; native or Docker for api/web.

## P7 Features
- Profile onboarding: `/onboarding`; fields display name, interests, hobbies, location, availability, optional bio/profession/workplace/photo.
- Group join approval: groups have `join_policy` OPEN/APPROVAL; endpoints `/v1/groups/{id}/join-requests`, approve/reject.
- Notifications: persistent table, unread count, mark read/all, polling; types include NEW_MESSAGE, NEW_MEMBER, JOIN_REQUEST_*, NEW_EVENT, EVENT_CANCELLED.
- Reviews: `/v1/events/{occurrenceId}/reviews`, `/v1/groups/{groupId}/reviews`, reporting.
- Images: `/v1/images` upload/get/delete, local temp storage, 5MB limit, formats jpg/png/webp/gif.

## Key APIs
- `PATCH /v1/users/me` accepts onboarding and profile fields.
- `POST /v1/groups` creates group with join_policy.
- `POST /v1/groups/{id}/join-requests` submits approval request.
- `GET /v1/notifications`, `PATCH /v1/notifications/{id}/read`, `POST /v1/notifications/read-all`.
- `GET /v1/images/{id}` requires authentication.

## Notes
- Docker API uses `/tmp/kinship-uploads` for images.
- Frontend profile photo upload uses bearer token and FormData.
- Notification cards navigate to group/chat/event based on type/entity.
- Header polls unread notification count every 30s.

## Verification
- `mvn verify`: 25 unit + 18 integration tests pass.
- `npm run lint`, `npm run typecheck`, `npm run build`: pass.
- Docker services healthy on ports 3000/8080/5433/6379.