#!/bin/bash

# verify prerequisites

git diff --exit-code > /dev/null

if [[ $? != 0 ]]; then
  echo you have uncommitted changes
  exit
fi

git diff --cached --exit-code > /dev/null

if [[ $? != 0 ]]; then
  git diff --cached

  echo -n "Proceed? [y/N] "
  read -n 1 proceed
  echo

  if [[ $proceed != "Y" && $proceed != "y" ]]; then
    exit 1
  fi
fi

java -version 2>&1 | grep 1.8.0 > /dev/null

if [[ $? != 0 ]]; then
  echo "you must build release with java 8 (glowroot-central requires java 8+)"
  exit
fi

set -e


base_dir=$(dirname $0)/..

if [ -z "$release_version" ]
then
  echo -n "release version: "
  read release_version
fi

if [ -z "$built_by" ]
then
  echo -n "built by: "
  read built_by
fi

if [ -z "$gpg_keyid" ]
then
  echo -n "GPG key ID: "
  read gpg_keyid
fi

if [ -z "$gpg_passphrase" ]
then
  echo -n "GPG passphrase: "
  read -s gpg_passphrase
  echo
fi


bower_snapshot_dependencies=$(grep -e "trask.*#[0-9a-f]\{40\}" $base_dir/ui/bower.json || true)

if [[ $bower_snapshot_dependencies ]]; then
  echo
  echo you need to update bower.json with tagged revisions of forked javascript repos:
  echo
  echo "$bower_snapshot_dependencies"
  echo
  echo "note: when tagging forked javascript repos, first commit -m \"Update generated files\" if necessary (e.g. needed for flot.tooltip)"
  exit 1
fi

sed -r -i s/glowroot-[0-9]+.[0-9]+.[0-9]+\(-beta\(\.[0-9]+\)?\)?-dist.zip/glowroot-$release_version-dist.zip/g $base_dir/README.md
sed -r -i s#https://github.com/glowroot/glowroot/releases/download/v[0-9]+.[0-9]+.[0-9]+\(-beta\(\.[0-9]+\)?\)?/#https://github.com/glowroot/glowroot/releases/download/v$release_version/# $base_dir/README.md

git diff

echo -n "Proceed? [y/N] "
read -n 1 proceed
echo

if [[ $proceed != "Y" && $proceed != "y" ]]; then
  exit 1
fi

git add -u

# the "|| true" is needed on maven 3.8.3 due to https://issues.apache.org/jira/browse/MNG-7234
mvn versions:set -DgenerateBackupPoms=false -DnewVersion=$release_version || true

git diff

echo -n "Proceed? [y/N] "
read -n 1 proceed
echo

if [[ $proceed != "Y" && $proceed != "y" ]]; then
  exit 1
fi

git add -u
git commit -m "Release version $release_version"




rm -rf ~/.m2/repository/org/glowroot_
mv ~/.m2/repository/org/glowroot ~/.m2/repository/org/glowroot_ || true
rm -rf ui/bower_components ui/node_modules
commit=$(git rev-parse HEAD)

# javadoc is needed here since deploy :glowroot-agent attaches the javadoc from :glowroot-agent-core
mvn clean install -pl :glowroot-agent,:glowroot-central -am \
                  -Pjavadoc \
                  -DskipTests

# gpg.keyId is needed for the rpm signing maven plugin
USERNAME=$built_by mvn clean deploy -pl :glowroot-parent,:glowroot-agent-api,:glowroot-agent-plugin-api,:glowroot-agent-it-harness,:glowroot-agent,:glowroot-central \
                                    -Pjavadoc \
                                    -Prelease \
                                    -Dglowroot.build.commit=$commit \
                                    -DskipTests \
                                    -Dgpg.keyId=$gpg_keyid \
                                    -Dgpg.passphrase=$gpg_passphrase
