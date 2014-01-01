#!/bin/bash -e

case "$1" in

  "install") if [[ "$TRAVIS_REPO_SLUG" == "glowroot/glowroot" && "$TRAVIS_BRANCH" == "master" ]]
             then
               # deploy unshaded artifacts to maven snapshot repository
               mvn deploy -Dshade.skip=true \
                          -Dgit.commit.hash=$TRAVIS_COMMIT \
                          --settings travis-build/settings.xml -B
               # build shaded package which will be uploaded to s3 in travis-ci deploy step
               mvn clean install -Dgit.commit.hash=$TRAVIS_COMMIT -B
             else
               mvn install -B
             fi
             ;;

             # pipe through sed to format checker errors so the source file locations
             # turn into links when copied into eclipse's stack trace view
  "checker") mvn process-classes -Pchecker \
                                 -Dgrunt.skip=true -B \
                                 | sed 's/\[ERROR\] .*[\/]\([^\/.]*\.java\):\[\([0-9]*\),\([0-9]*\)\]/[ERROR] (\1:\2) [column \3]/'
             # preserve exit status from mvn
             test ${PIPESTATUS[0]} -eq 0
             ;;

             # test is used for jdk6 build since install is not needed
             # and this helps validate mvn test at same time
  "test")    mvn test -B
             ;;

esac
