#!/bin/bash -e

# java.security.egd is needed for low-entropy docker containers
# /dev/./urandom (as opposed to simply /dev/urandom) is needed prior to Java 8
# (see https://docs.oracle.com/javase/8/docs/technotes/guides/security/enhancements-8.html)
surefire_jvm_args="-Xmx512m -Djava.security.egd=file:/dev/./urandom"

case "$1" in

       "test") # shading is done during the package phase, so 'mvn test' is used to run tests
               # against unshaded glowroot-core and 'mvn package' is used to run tests against
               # shaded glowroot-core
               if [[ "$GLOWROOT_UNSHADED" == "true" ]]
               then
                 mvn clean test -Dglowroot.test.harness=$GLOWROOT_HARNESS \
                                -DargLine="$surefire_jvm_args" \
                                -B
               else
                 # using install instead of package for subsequent jdbc-plugin tests
                 mvn clean install -Dglowroot.test.harness=$GLOWROOT_HARNESS \
                                   -DargLine="$surefire_jvm_args" \
                                   -B
                 mvn clean test -pl plugins/jdbc-plugin \
                                -Dglowroot.test.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.jdbcConnectionType=H2 \
                                -DargLine="$surefire_jvm_args" \
                                -B
                 mvn clean test -pl plugins/jdbc-plugin \
                                -Dglowroot.test.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.jdbcConnectionType=COMMONS_DBCP_WRAPPED \
                                -DargLine="$surefire_jvm_args" \
                                -B
                 mvn clean test -pl plugins/jdbc-plugin \
                                -Dglowroot.test.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.jdbcConnectionType=TOMCAT_JDBC_POOL_WRAPPED \
                                -DargLine="$surefire_jvm_args" \
                                -B
               fi
               ;;

     "deploy") # using the default test harness (local) since it is faster, and complete coverage
               # with both harnesses is done elsewhere in the build
               #
               # using glowroot.ui.skip so deployed test-harness artifact will not include any third
               # party javascript libraries
               mvn clean install -Dglowroot.ui.skip=true \
                                 -DargLine="$surefire_jvm_args" \
                                 -B
               # only deploy snapshot versions (release versions need pgp signature)
               version=`mvn help:evaluate -Dexpression=project.version | grep -v '\['`
               if [[ "$TRAVIS_REPO_SLUG" == "glowroot/glowroot" && "$TRAVIS_BRANCH" == "master" && "$version" == *-SNAPSHOT ]]
               then
                 # deploy only parent, plugin-api and test-harness artifacts to maven repository
                 mvn clean deploy -pl .,plugin-api,test-harness \
                                  -Pjavadoc \
                                  -Dglowroot.ui.skip=true \
                                  -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                  -DargLine="$surefire_jvm_args" \
                                  --settings misc/travis-build/settings.xml \
                                  -B
               fi
               # build shaded distribution zip which will be uploaded to s3 in travis-ci deploy step
               mvn clean install -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                 -DargLine="$surefire_jvm_args" \
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
                 # code coverage for @Pointcut classes are only captured when run with javaagent
                 # harness since in that case jacoco javaagent goes first (see JavaagentMain) and
                 # uses the original bytecode to construct the class ids, whereas when run with
                 # local harness jacoco javaagent uses the bytecode that is woven by
                 # IsolatedWeavingClassLoader to construct the class ids
                 #
                 # shading is done during the package phase, so 'mvn test' is used to run tests
                 # against unshaded glowroot-core and 'mvn package' is used to run tests against
                 # shaded glowroot-core
                 mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent test \
                                 -Dglowroot.test.harness=javaagent \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -Djacoco.propertyName=jacocoArgLine \
                                 -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                 -B
                 # basic mvn install so the additional runs can use the installed artifacts
                 mvn clean install -DskipTests=true
                 # also running integration-tests with (default) local test harness to capture a
                 # couple methods exercised only by the local test harness
                 mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent test \
                                 -pl testing/integration-tests \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -Djacoco.propertyName=jacocoArgLine \
                                 -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                 -B
                 # also running logger-plugin tests with shading and javaagent in order to get
                 # code coverage for Slf4jTest and Slf4jMarkerTest
                 # (see comments in those classes for more detail)
                 mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent package \
                                 -pl plugin-api,core,plugins/logger-plugin \
                                 -Dglowroot.test.harness=javaagent \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -Djacoco.propertyName=jacocoArgLine \
                                 -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                 -B

                 # the sonar.jdbc.password system property is set in the pom.xml using the
                 # environment variable SONAR_DB_PASSWORD (instead of setting the system
                 # property on the command line which which would make it visible to ps)
                 mvn sonar:sonar -pl .,plugin-api,core,plugins/cassandra-plugin,plugins/jdbc-plugin,plugins/logger-plugin,plugins/servlet-plugin \
                                 -Dsonar.jdbc.url=$SONAR_JDBC_URL \
                                 -Dsonar.jdbc.username=$SONAR_JDBC_USERNAME \
                                 -Dsonar.host.url=$SONAR_HOST_URL \
                                 -Dsonar.jacoco.reportPath=$PWD/jacoco-combined.exec \
                                 -DargLine="$surefire_jvm_args" \
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
                 # need to limit memory of all JVM forks for travis docker build
                 # see https://github.com/travis-ci/travis-ci/issues/3396
                 sed -i 's#"java" "-jar" "${mydir}"/../dist/checker.jar#"java" "-Xmx512m" "-jar" "${mydir}"/../dist/checker.jar -J-Xmx512m#' $HOME/checker-framework/checker/bin/javac
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

               mvn clean compile -pl .,misc/license-resource-bundle,plugin-api,core,test-harness,plugins/cassandra-plugin,plugins/jdbc-plugin,plugins/logger-plugin,plugins/servlet-plugin \
                                 -Pchecker \
                                 -Dchecker.install.dir=$HOME/checker-framework \
                                 -Dchecker.stubs.dir=$PWD/misc/checker-stubs \
                                 -Dglowroot.ui.skip=true \
                                 -DargLine="$surefire_jvm_args" \
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
                                -DargLine="$surefire_jvm_args" \
                                -B
               else
                 echo skipping, saucelabs only runs against master repository and master branch
               fi
               ;;

esac
