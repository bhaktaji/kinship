#!/usr/bin/env bash
# P6 failure injection against the local compose stack.
# Every step prints machine-readable evidence; nothing deletes data or volumes.
set -u
API=${API:-http://localhost:8080}
PASS="Load12345!"
EMAIL="load-1@perf.kinship.local"

code() { # code METHOD PATH [TOKEN] [BODY]
  local method=$1 path=$2 token=${3:-} body=${4:-}
  curl -s -o /tmp/chaos-body -w "%{http_code} %{time_total}" -X "$method" "$API$path" \
    ${token:+-H "Authorization: Bearer $token"} \
    ${body:+-H "Content-Type: application/json" -d "$body"}
}

login_code() {
  curl -s -o /tmp/chaos-login -w "%{http_code}" -X POST "$API/v1/auth/login" \
    -H "Content-Type: application/json" -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}"
}

TOKEN=$(curl -s -X POST "$API/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(JSON.parse(s).access_token))")
GROUP=$(curl -s -X POST "$API/v1/groups" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Chaos group","category":"sport","max_members":6,"visibility":"public","location":{"lat":52.52,"lng":13.405}}' \
  | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(JSON.parse(s).group.group_id))")
echo "fixture group=$GROUP"

echo "--- steady state ---"
echo "discovery=$(code GET "/v1/activities?lat=52.52&lng=13.405&radius_km=10" "$TOKEN") readyz=$(code GET /readyz)"
echo "message=$(code POST "/v1/groups/$GROUP/messages" "$TOKEN" "{\"body\":\"before redis outage\",\"client_msg_id\":\"$(uuidgen 2>/dev/null || echo 11111111-1111-1111-1111-111111111111)\"}")"
docker stats --no-stream --format "{{.Name}} mem={{.MemUsage}} cpu={{.CPUPerc}}" kinship-api-1 | sed 's/^/stats /'

echo "--- Redis stopped ---"
docker stop kinship-redis-1 > /dev/null
sleep 2
echo "discovery=$(code GET "/v1/activities?lat=52.52&lng=13.405&radius_km=10" "$TOKEN")"
echo "login_status=$(login_code)"
echo "ticket=$(code POST /v1/realtime/tickets "$TOKEN" "{\"group_id\":\"$GROUP\"}")"
echo "message=$(code POST "/v1/groups/$GROUP/messages" "$TOKEN" "{\"body\":\"during redis outage\",\"client_msg_id\":\"$(uuidgen 2>/dev/null || echo 22222222-2222-2222-2222-222222222222)\"}")"
echo "readyz=$(code GET /readyz)"
grep -o '"code":"[A-Z_]*"' /tmp/chaos-body 2>/dev/null | head -1 | sed 's/^/last_error=/' || true

docker start kinship-redis-1 > /dev/null
for i in $(seq 1 20); do
  [ "$(code GET /readyz | cut -d' ' -f1)" = "200" ] && break
  sleep 1
done
echo "--- Redis recovered after ${i}s ---"
echo "readyz=$(code GET /readyz) login_status=$(login_code)"
TOKEN=$(node -e "let s='';require('fs').readFileSync('/tmp/chaos-login','utf8')" 2>/dev/null; \
  curl -s -X POST "$API/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(JSON.parse(s).access_token))")
echo "discovery=$(code GET "/v1/activities?lat=52.52&lng=13.405&radius_km=10" "$TOKEN")"
echo "ticket=$(code POST /v1/realtime/tickets "$TOKEN" "{\"group_id\":\"$GROUP\"}")"
echo "history=$(code GET "/v1/groups/$GROUP/messages?limit=10" "$TOKEN")"

echo "--- PostgreSQL stopped ---"
docker stop kinship-postgres-1 > /dev/null
sleep 2
echo "healthz=$(code GET /healthz) readyz=$(code GET /readyz)"
echo "discovery=$(code GET "/v1/activities?lat=52.52&lng=13.405&radius_km=10" "$TOKEN")"
echo "login_status=$(login_code)"
echo "message=$(code POST "/v1/groups/$GROUP/messages" "$TOKEN" "{\"body\":\"during postgres outage\",\"client_msg_id\":\"$(uuidgen 2>/dev/null || echo 33333333-3333-3333-3333-333333333333)\"}")"
echo "api_container=$(docker inspect -f '{{.State.Status}} restarts={{.RestartCount}}' kinship-api-1)"

docker start kinship-postgres-1 > /dev/null
for i in $(seq 1 40); do
  [ "$(code GET /readyz | cut -d' ' -f1)" = "200" ] && break
  sleep 1
done
echo "--- PostgreSQL recovered after ${i}s ---"
echo "readyz=$(code GET /readyz) discovery=$(code GET "/v1/activities?lat=52.52&lng=13.405&radius_km=10" "$TOKEN")"
echo "history=$(code GET "/v1/groups/$GROUP/messages?limit=10" "$TOKEN")"

echo "--- stale RUNNING job recovery ---"
JOB=$(docker exec kinship-postgres-1 psql -U kinship -d kinship -tAc \
  "WITH ins AS (
     INSERT INTO match_jobs (id, user_id, status, preferences, created_at, started_at)
     SELECT gen_random_uuid(), id, 'RUNNING', '{}'::jsonb, now() - interval '12 minutes', now() - interval '12 minutes'
       FROM users WHERE email = '$EMAIL' RETURNING id
   ) SELECT id FROM ins;" | tr -d '[:space:]')
echo "inserted_job=$JOB"
docker restart kinship-api-1 > /dev/null
for i in $(seq 1 40); do
  [ "$(code GET /readyz | cut -d' ' -f1)" = "200" ] && break
  sleep 1
done
sleep 3
echo "job_after_restart=$(docker exec kinship-postgres-1 psql -U kinship -d kinship -tAc "SELECT status || ' ' || coalesce(error_code,'-') FROM match_jobs WHERE id='$JOB';" | tr -d '\r')"

echo "--- data integrity ---"
docker exec kinship-postgres-1 psql -U kinship -d kinship -tAc \
  "SELECT 'users=' || count(*) FROM users; SELECT 'groups=' || count(*) FROM groups; SELECT 'messages=' || count(*) FROM messages;"
