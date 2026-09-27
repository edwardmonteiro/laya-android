#!/usr/bin/env bash
# Pushes this job's log to its own orphan branch so it can be read with plain git.
# The workflow only triggers on main, so these branches never start a build.
set -u
BRANCH="$1"
TOKEN="$2"
LOG_FILE="${LOG:-../job.log}"
WORK="$(mktemp -d)"
cd "$WORK" || exit 0
git init -q
git checkout -q -b "$BRANCH"
if [ -f "$LOG_FILE" ]; then tail -c 2000000 "$LOG_FILE" > job.log; else echo "no log" > job.log; fi
echo "run ${GITHUB_RUN_ID:-?} sha ${GITHUB_SHA:-?} job ${GITHUB_JOB:-?}" > run.txt
git add job.log run.txt
git -c user.name=laya-ci -c user.email=laya-ci@users.noreply.github.com commit -qm "log ${GITHUB_RUN_ID:-}" || exit 0
git push -q -f "https://x-access-token:${TOKEN}@github.com/${GITHUB_REPOSITORY}" "HEAD:refs/heads/${BRANCH}" || true
