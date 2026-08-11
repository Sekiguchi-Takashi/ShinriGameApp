#!/bin/bash
cd "$(dirname "$0")" || exit 1

REPO=ShinriGameApp
MSG="${1:-update}"

TOKEN=$(git config --global github.token)
USER=$(git config --global github.user)
if [ -z "$USER" ]; then
  USER=Sekiguchi-Takashi
fi
if [ -z "$TOKEN" ]; then
  exit 1
fi

curl -s -o /dev/null -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d "{\"name\":\"$REPO\",\"private\":true}" \
  https://api.github.com/user/repos

if [ ! -d .git ]; then
  git init -b main
fi

git remote remove origin 2>/dev/null
git remote add origin "https://$USER:$TOKEN@github.com/$USER/$REPO.git"

git add -A
git commit -m "$MSG" || true
git push -u origin main --force
