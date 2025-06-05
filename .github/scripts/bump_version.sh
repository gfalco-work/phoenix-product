#!/bin/bash

# Path to your build.gradle file
BUILD_FILE="build.gradle"

# Get the last commit message
COMMIT_MESSAGE=$(git log -1 --pretty=%B)

# Extract the current version from build.gradle using sed
CURRENT_VERSION=$(grep -o 'version = "[^"]*"' $BUILD_FILE | sed 's/version = "//;s/"//')

# Determine the version increment based on the commit message
if echo "$COMMIT_MESSAGE" | grep -q "minor"; then
  # Increment minor version
  IFS='.' read -r major minor patch <<< "$CURRENT_VERSION"
  NEW_VERSION="$major.$((minor + 1)).0"
elif echo "$COMMIT_MESSAGE" | grep -q "patch"; then
  # Increment patch version
  IFS='.' read -r major minor patch <<< "$CURRENT_VERSION"
  NEW_VERSION="$major.$minor.$((patch + 1))"
elif echo "$COMMIT_MESSAGE" | grep -q "major"; then
  # Increment major version
  IFS='.' read -r major minor patch <<< "$CURRENT_VERSION"
  NEW_VERSION="$((major + 1)).0.0"
else
  echo "No version increment needed."
  exit 0
fi

echo 'New version is: ' "$NEW_VERSION"

# Update the version in build.gradle
sed -i '' "s/version = \"[^\"]*\"/version = \"$NEW_VERSION\"/" $BUILD_FILE

# Print new version
echo "Updated version to $NEW_VERSION"
