#!/bin/bash -e

case "$1" in

       "test") # shading is done during the package phase, so 'mvn test' is used to run tests
               # against unshaded glowroot-core and 'mvn package' is used to run tests against
               # shaded glowroot-core (no need to use glowroot.shading.skip)
               if [[ "$GLOWROOT_UNSHADED" == "true" ]]
               then
                 mvn clean test -Dglowroot.test.harness=$GLOWROOT_HARNESS \
                                -B
               else
                 mvn clean package -Dglowroot.test.harness=$GLOWROOT_HARNESS \
                                   -B
               fi
               ;;

     "deploy") # using local test harness here since it is faster, and complete coverage with both
               # harnesses is done elsewhere in the build
               if [[ "$TRAVIS_REPO_SLUG" == "glowroot/glowroot" && "$TRAVIS_BRANCH" == "master" ]]
               then
                 # deploy unshaded artifacts to maven repository (with no embedded third party
                 # libraries, including no third party javascript libraries)
                 mvn clean deploy -DdeployAtEnd=true \
                                  -Dglowroot.shading.skip=true \
                                  -Dglowroot.ui.skip=true \
                                  -Dglowroot.test.harness=local \
                                  -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                  --settings travis-build/settings.xml \
                                  -B
               else
                 # simulate the deploy step from above
                 mvn clean install -DinstallAtEnd=true \
                                   -Dglowroot.shading.skip=true \
                                   -Dglowroot.test.harness=local \
                                   -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                   -B
               fi
               # build shaded distribution zip which will be uploaded to s3 in travis-ci deploy step
               mvn clean install -Dglowroot.test.harness=local \
                                 -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                 -B
               ;;

  "sonarqube") if [[ "$TRAVIS_REPO_SLUG" == "glowroot/glowroot" && "$TRAVIS_BRANCH" == "master" ]]
               then
                 # need to skip shading when running jacoco, otherwise the bytecode changes done to
                 # the classes during shading and proguard-ing will modify the jacoco class id and
                 # the sonarqube reports won't report usage of those bytecode modified classes
                 #
                 # jacoco destFile needs absolute path, otherwise it is relative to each submodule
                 #
                 # using local test harness since that falls back to javaagent for a few tests and
                 # hitting both harnesses gives the best code coverage
                 mvn org.jacoco:jacoco-maven-plugin:prepare-agent clean install \
                                 -Dglowroot.shading.skip=true \
                                 -Dglowroot.test.harness=local \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -B
                 # re-using jacoco code coverage reports from above, but sonar still runs the tests
                 # to report on timings and failure rates
                 #
                 # using local harness since it is faster (and the default anyways)
                 #
                 # the sonar.jdbc.password system property is set in the pom.xml using the
                 # environment variable SONARQUBE_DB_PASSWORD (instead of setting the system
                 # property on the command line which which would make it visible to ps)
                 mvn sonar:sonar -pl .,plugin-api,core,plugins/jdbc-plugin,plugins/servlet-plugin,plugins/logger-plugin \
                                 -Dsonar.jdbc.url=jdbc:postgresql://sonarqube.glowroot.org/sonar \
                                 -Dsonar.jdbc.username=sonar \
                                 -Dsonar.host.url=http://sonarqube.glowroot.org \
                                 -Dsonar.jacoco.reportPath=$PWD/jacoco-combined.exec \
                                 -Dglowroot.test.harness=local \
                                 -B
               else
                 echo skipping, sonarqube analysis only runs against master repository and master branch
               fi
               ;;

    "checker") # pipe through sed to format checker errors so the source file locations
               # turn into links when copied into eclipse's stack trace view
               mvn clean process-classes -Pchecker \
                                         -Dglowroot.shading.skip=true \
                                         -Dglowroot.ui.skip=true \
                                         -B \
                                         | sed 's/\[ERROR\] .*[\/]\([^\/.]*\.java\):\[\([0-9]*\),\([0-9]*\)\]/[ERROR] (\1:\2) [column \3]/'
               # preserve exit status from mvn
               test ${PIPESTATUS[0]} -eq 0
               ;;

  "saucelabs") if [[ "$TRAVIS_REPO_SLUG" == "glowroot/glowroot" && "$TRAVIS_BRANCH" == "master" ]]
               then
                 mvn clean install -DskipTests=true \
                                   -B
                 cd webdriver-tests
                 mvn clean test -Dsaucelabs.platform="$SAUCELABS_PLATFORM" \
                                -Dsaucelabs.browser.name=$SAUCELABS_BROWSER_NAME \
                                -Dsaucelabs.browser.version=$SAUCELABS_BROWSER_VERSION \
                                -Dsaucelabs.device.name="$SAUCELABS_DEVICE_NAME" \
                                -Dsaucelabs.device.version=$SAUCELABS_DEVICE_VERSION \
                                -Dsaucelabs.device.type=$SAUCELABS_DEVICE_TYPE \
                                -Dsaucelabs.device.orientation=$SAUCELABS_DEVICE_ORIENTATION \
                                -Dsaucelabs.device.app=$SAUCELABS_DEVICE_APP \
                                -Dsaucelabs.tunnel.identifier=$TRAVIS_JOB_NUMBER \
                                -B
               else
                 echo skipping, saucelabs only runs against master repository and master branch
               fi
               ;;

esac
