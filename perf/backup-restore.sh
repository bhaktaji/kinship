#!/usr/bin/env bash
# P6 backup/restore rehearsal. Dumps the live database, restores it into a
# scratch database, compares row counts, then drops only the scratch database.
set -eu
DUMP=perf/results/kinship.dump
SQL="SELECT 'users='||count(*) FROM users; SELECT 'groups='||count(*) FROM groups;
     SELECT 'messages='||count(*) FROM messages; SELECT 'rsvps='||count(*) FROM rsvps;
     SELECT 'match_jobs='||count(*) FROM match_jobs;"

echo "--- dump ---"
START=$(date +%s)
docker exec kinship-postgres-1 pg_dump -U kinship -d kinship -Fc > "$DUMP"
echo "dump_bytes=$(wc -c < "$DUMP") dump_seconds=$(( $(date +%s) - START ))"

echo "--- live counts ---"
docker exec kinship-postgres-1 psql -U kinship -d kinship -tAc "$SQL" | tr -d '\r'

echo "--- restore into scratch database ---"
docker exec kinship-postgres-1 psql -U kinship -d postgres -tAc \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'kinship_restore';" > /dev/null
docker exec kinship-postgres-1 dropdb -U kinship --if-exists kinship_restore
docker exec kinship-postgres-1 createdb -U kinship kinship_restore
START=$(date +%s)
docker exec -i kinship-postgres-1 pg_restore -U kinship -d kinship_restore --no-owner < "$DUMP"
echo "restore_seconds=$(( $(date +%s) - START ))"

echo "--- restored counts ---"
docker exec kinship-postgres-1 psql -U kinship -d kinship_restore -tAc "$SQL" | tr -d '\r'

echo "--- spot check ---"
docker exec kinship-postgres-1 psql -U kinship -d kinship_restore -tAc \
  "SELECT 'latest_seq=' || coalesce(max(sequence_id),0) FROM messages;" | tr -d '\r'

docker exec kinship-postgres-1 dropdb -U kinship kinship_restore
echo "scratch_database_dropped=true"
