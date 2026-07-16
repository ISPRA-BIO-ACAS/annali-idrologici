#!/bin/sh
# Healthcheck for postgis/postgis on first boot.
#
# The image starts Postgres once to run init (including PostGIS), then stops with:
#   "PostgreSQL init process complete; ready for start up."
# and starts the real server:
#   "database system is ready to accept connections"
#
# pg_isready can succeed during the temporary init instance, so we must not mark
# the container healthy until after that restart cycle (or on an established volume).

set -eu

PGUSER="${POSTGRES_USER:-sensorthings}"
PGDATABASE="${POSTGRES_DB:-sensorthings}"
DATA="/var/lib/postgresql/data"
STATE="/tmp/postgres-healthcheck.state"

check_ready() {
	pg_isready -q -U "$PGUSER" -d "$PGDATABASE" || return 1
	psql -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$PGDATABASE" -tAc "SELECT PostGIS_Version();" 2>/dev/null | grep -q .
}

# Quick path: data directory has been around for a while (normal restarts).
cluster_is_established() {
	[ -f "$DATA/PG_VERSION" ] && find "$DATA/PG_VERSION" -mmin +20 2>/dev/null | grep -q .
}

if cluster_is_established; then
	check_ready
	exit $?
fi

# Require a short stable connection window on each probe (catch brief shutdown gaps).
sample_ready() {
	check_ready || return 1
	i=0
	while [ "$i" -lt 5 ]; do
		sleep 1
		check_ready || return 1
		i=$((i + 1))
	done
	return 0
}

if ! sample_ready; then
	printf '%s\n' "phase=disconnected stable=0" > "$STATE"
	exit 1
fi

phase=waiting
stable=0
started=$(date +%s)
if [ -f "$STATE" ]; then
	# shellcheck disable=SC1090
	. "$STATE"
fi

case "$phase" in
disconnected)
	printf '%s\n' "phase=reconnected stable=1 started=$started" > "$STATE"
	exit 1
	;;
reconnected)
	stable=$((stable + 1))
	if [ "$stable" -ge 5 ]; then
		rm -f "$STATE"
		exit 0
	fi
	printf '%s\n' "phase=reconnected stable=$stable started=$started" > "$STATE"
	exit 1
	;;
waiting)
	# Still on the first connection leg: stay unhealthy until we observe a shutdown
	# (init handoff) or we fall back after a long stable period (healthchecks that
	# start only after init already finished).
	stable=$((stable + 1))
	now=$(date +%s)
	if [ "$((now - started))" -gt 600 ] && [ "$stable" -ge 30 ]; then
		printf '%s\n' "phase=reconnected stable=1 started=$started" > "$STATE"
		exit 1
	fi
	printf '%s\n' "phase=waiting stable=$stable started=${started:-$now}" > "$STATE"
	exit 1
	;;
*)
	printf '%s\n' "phase=waiting stable=0 started=$started" > "$STATE"
	exit 1
	;;
esac
