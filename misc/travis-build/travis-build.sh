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
                                  --settings misc/travis-build/settings.xml \
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

      "sonar") if [[ $SONAR_JDBC_URL ]]
               then
                 # need to skip shading when running jacoco, otherwise the bytecode changes done to
                 # the classes during shading will modify the jacoco class id and the sonar reports
                 # won't report usage of those bytecode modified classes
                 #
                 # jacoco destFile needs absolute path, otherwise it is relative to each submodule
                 #
                 # code coverage for @Pointcut classes are only captured when run with javaagent harness
                 # since in that case jacoco javaagent goes first (see JavaagentMain) and uses the
                 # original bytecode to construct the class ids, whereas when run with local harness
                 # jacoco javaagent uses the bytecode that is woven by IsolatedWeavingClassLoader to
                 # construct the class ids
                 mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent install \
                                 -Dglowroot.shading.skip=true \
                                 -Dglowroot.test.harness=javaagent \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -B
                 # also running integration-tests with local test harness to capture a couple methods
                 # exercised only by the local test harness
                 mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent install \
                                 -pl testing/integration-tests \
                                 -Dglowroot.shading.skip=true \
                                 -Dglowroot.test.harness=local \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -B
                 # also running logger-plugin-tests with shading and javaagent in order to get
                 # code coverage for Slf4jTest and Slf4jMarkerTest
                 # (see comments in those classes for more detail)
                 mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent install \
                                 -pl plugin-api,core,plugins/logger-plugin-tests \
                                 -Dglowroot.test.harness=javaagent \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -B

                 # the sonar.jdbc.password system property is set in the pom.xml using the
                 # environment variable SONAR_DB_PASSWORD (instead of setting the system
                 # property on the command line which which would make it visible to ps)
                 #
                 # need to use real IP address for jdbc connection since sonar.glowroot.org points to cloudflare
                 mvn sonar:sonar -pl .,plugin-api,core,plugins/jdbc-plugin,plugins/servlet-plugin,plugins/logger-plugin \
                                 -Dsonar.jdbc.url=$SONAR_JDBC_URL \
                                 -Dsonar.jdbc.username=$SONAR_JDBC_USERNAME \
                                 -Dsonar.host.url=$SONAR_HOST_URL \
                                 -Dsonar.jacoco.reportPath=$PWD/jacoco-combined.exec \
                                 -B
               else
                 echo skipping, sonar analysis only runs against master repository and master branch
               fi
               ;;

    "checker") # pipe through sed to format checker errors so the source file locations
               # turn into links when copied into eclipse's stack trace view
               if [[ ! -d "$HOME/checker-framework" ]]
               then
                 # install checker framework
                 curl http://types.cs.washington.edu/checker-framework/current/checker-framework.zip > $HOME/checker-framework.zip
                 unzip $HOME/checker-framework.zip -d $HOME
                 # strip version from directory name
                 mv $HOME/checker-framework-* $HOME/checker-framework
               fi

               set +e
               git diff --exit-code > /dev/null
               if [ $? -ne 0 ]
               then
                 echo you have unstaged changes!
                 exit
               fi
               set -e

               find -name *.java -print0 | xargs -0 sed -i 's|/\*>>>@UnknownInitialization|/*>>>@org.checkerframework.checker.initialization.qual.UnknownInitialization|g'
               find -name *.java -print0 | xargs -0 sed -i 's|/\*@Untainted\*/|/*@org.checkerframework.checker.tainting.qual.Untainted*/|g'
               find -name *.java -print0 | xargs -0 sed -i 's|/\*@\([A-Za-z]*\)\*/|/*@org.checkerframework.checker.nullness.qual.\1*/|g'

               mvn clean compile -pl .,misc/license-resource-bundle,plugin-api,core,test-harness,plugins/jdbc-plugin,plugins/servlet-plugin,plugins/logger-plugin \
                                 -Pchecker \
                                 -Dchecker.install.dir=$HOME/checker-framework \
                                 -Dchecker.stubs.dir=$PWD/misc/checker-stubs \
                                 -Dglowroot.ui.skip=true \
                                 -B \
                                 | sed 's/\[ERROR\] .*[\/]\([^\/.]*\.java\):\[\([0-9]*\),\([0-9]*\)\]/[ERROR] (\1:\2) [column \3]/'
               # preserve exit status from mvn (needed because of pipe to sed)
               mvn_status=${PIPESTATUS[0]}
               git checkout -- .
               test $mvn_status -eq 0
               ;;

  "saucelabs") if [[ $SAUCE_USERNAME ]]
               then
                 mvn clean install -DskipTests=true \
                                   -B
                 cd testing/webdriver-tests
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
