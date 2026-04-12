#!/usr/bin/env bash
  set -e

  BASE_VERSION=0.0.31.1.wtransport
  VERSION=${BASE_VERSION}-SNAPSHOT
  BASE=https://secfotech.jfrog.io/artifactory/sophon-power-custom-3rd-party-local
  M2_REPO=/Users/alesnovak/.m2/repository/io/netty/incubator/netty-incubator-codec-http3/$VERSION
  PATH_PREFIX=io/netty/incubator/netty-incubator-codec-http3/$VERSION
  ARTIFACT=netty-incubator-codec-http3
  SNAPSHOT_VERSION=${BASE_VERSION}-SNAPSHOT
  USER=$JFROG_SECFOTECH_USER
  TOKEN=$JFROG_SECFOTECH_TOKEN

  # One timestamp for all files
  TIMESTAMP=$(date -u +%Y%m%d.%H%M%S)
  BUILD_NUMBER=1
  UNIQUE_VERSION="${BASE_VERSION}-${TIMESTAMP}-${BUILD_NUMBER}"

  upload() {
    local SRC=$1 DEST=$2
    echo "Uploading $DEST..."
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
      -u "$USER:$TOKEN" \
      -T "$SRC" \
      "$BASE/$PATH_PREFIX/$DEST")
    echo "  -> HTTP $HTTP_CODE"
  }

  upload "$M2_REPO/${ARTIFACT}-${SNAPSHOT_VERSION}.pom"              "${ARTIFACT}-${UNIQUE_VERSION}.pom"
  upload "$M2_REPO/${ARTIFACT}-${SNAPSHOT_VERSION}.jar"              "${ARTIFACT}-${UNIQUE_VERSION}.jar"
  upload "$M2_REPO/${ARTIFACT}-${SNAPSHOT_VERSION}-sources.jar"      "${ARTIFACT}-${UNIQUE_VERSION}-sources.jar"
  upload "$M2_REPO/${ARTIFACT}-${SNAPSHOT_VERSION}-test-sources.jar" "${ARTIFACT}-${UNIQUE_VERSION}-test-sources.jar"

  # Push maven-metadata.xml so resolvers see a consistent snapshot
  LAST_UPDATED=$(date -u +%Y%m%d%H%M%S)
  METADATA="<?xml version=\"1.0\" encoding=\"UTF-8\"?>
  <metadata>
    <groupId>io.netty.incubator</groupId>
    <artifactId>${ARTIFACT}</artifactId>
    <version>${SNAPSHOT_VERSION}</version>
    <versioning>
      <snapshot>
        <timestamp>${TIMESTAMP}</timestamp>
        <buildNumber>${BUILD_NUMBER}</buildNumber>
      </snapshot>
      <lastUpdated>${LAST_UPDATED}</lastUpdated>
      <snapshotVersions>
        <snapshotVersion>
          <extension>pom</extension>
          <value>${UNIQUE_VERSION}</value>
          <updated>${LAST_UPDATED}</updated>
        </snapshotVersion>
        <snapshotVersion>
          <extension>jar</extension>
          <value>${UNIQUE_VERSION}</value>
          <updated>${LAST_UPDATED}</updated>
        </snapshotVersion>
        <snapshotVersion>
          <classifier>sources</classifier>
          <extension>jar</extension>
          <value>${UNIQUE_VERSION}</value>
          <updated>${LAST_UPDATED}</updated>
        </snapshotVersion>
      </snapshotVersions>
    </versioning>
  </metadata>"

  echo "Uploading maven-metadata.xml..."
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -u "$USER:$TOKEN" \
    -X PUT \
    -H "Content-Type: application/xml" \
    -d "$METADATA" \
    "$BASE/$PATH_PREFIX/maven-metadata.xml")
  echo "  -> HTTP $HTTP_CODE"

  echo ""
  echo "Use in Gradle:"
  echo "  implementation 'io.netty.incubator:${ARTIFACT}:${SNAPSHOT_VERSION}'"