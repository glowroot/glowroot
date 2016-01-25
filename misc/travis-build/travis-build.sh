#!/bin/bash -e

# java.security.egd is needed for low-entropy docker containers
# /dev/./urandom (as opposed to simply /dev/urandom) is needed prior to Java 8
# (see https://docs.oracle.com/javase/8/docs/technotes/guides/security/enhancements-8.html)
surefire_jvm_args="-Xmx512m -Djava.security.egd=file:/dev/./urandom"

case "$1" in

       "test") if [[ "$SKIP_SHADING" == "true" ]]
               then
                 skip_shading_opt=-Dglowroot.shade.skip
               fi
               mvn clean install -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                 -DargLine="$surefire_jvm_args" \
                                $skip_shading_opt \
                                 -B
               mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.jdbcConnectionType=H2 \
                                -DargLine="$surefire_jvm_args" \
                                $skip_shading_opt \
                                -B
               mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.jdbcConnectionType=COMMONS_DBCP_WRAPPED \
                                -DargLine="$surefire_jvm_args" \
                                $skip_shading_opt \
                                -B
               mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.jdbcConnectionType=TOMCAT_JDBC_POOL_WRAPPED \
                                -DargLine="$surefire_jvm_args" \
                                $skip_shading_opt \
                                -B
               ;;

     "deploy") # build shaded distribution zip which will be uploaded to s3 in travis-ci deploy step
               mvn clean install -Pjavadoc \
                                 -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                 -DargLine="$surefire_jvm_args" \
                                 -B
               # only deploy snapshot versions (release versions need pgp signature)
               version=`mvn help:evaluate -Dexpression=project.version | grep -v '\['`
               if [[ "$TRAVIS_REPO_SLUG" == "glowroot/glowroot" && "$TRAVIS_BRANCH" == "master" && "$TRAVIS_PULL_REQUEST" == "false" && "$version" == *-SNAPSHOT ]]
               then
                 # deploy only glowroot-parent, glowroot-agent-api, glowroot-agent-plugin-api, glowroot-agent and glowroot-agent-it-harness artifacts to maven repository
                 mvn clean deploy -pl :glowroot-parent,:glowroot-agent-api,:glowroot-agent-plugin-api,:glowroot-agent,:glowroot-agent-it-harness \
                                  -Pjavadoc \
                                  -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                  -DargLine="$surefire_jvm_args" \
                                  --settings misc/travis-build/settings.xml \
                                  -B
               fi
               ;;

      "sonar") if [[ $SONAR_JDBC_URL && "$TRAVIS_PULL_REQUEST" == "false" ]]
               then
                 # need to skip shading when running jacoco, otherwise the bytecode changes done to
                 # the classes during shading will modify the jacoco class id and the sonar reports
                 # won't report usage of those bytecode modified classes
                 #
                 # jacoco destFile needs absolute path, otherwise it is relative to each submodule
                 #
                 # code coverage for @Pointcut classes are only captured when run with javaagent
                 # integration test harness since in that case jacoco javaagent goes first
                 # (see JavaagentMain) and uses the original bytecode to construct the class ids,
                 # whereas when run with local integration test harness jacoco javaagent uses the
                 # bytecode that is woven by IsolatedWeavingClassLoader to construct the class ids
                 mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent test \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -Djacoco.propertyName=jacocoArgLine \
                                 -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                 -Dglowroot.shade.skip \
                                 -B
                 # intentionally calling failsafe plugin directly in order to skip surefire (unit test) execution
                 mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent-integration test-compile failsafe:integration-test failsafe:verify \
                                 -Dglowroot.it.harness=javaagent \
                                 -Djacoco.destFile=$PWD/jacoco-combined-it.exec \
                                 -Djacoco.propertyName=jacocoArgLine \
                                 -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                 -Dglowroot.shade.skip \
                                 -B
                 # the sonar.jdbc.password system property is set in the pom.xml using the
                 # environment variable SONAR_DB_PASSWORD (instead of setting the system
                 # property on the command line which which would make it visible to ps)
                 mvn clean verify sonar:sonar -pl !misc/checker-qual-jdk6,!misc/license-resource-bundle,!agent-parent/benchmarks,!agent-parent/ui-sandbox,!agent-parent/distribution \
                                 -Dsonar.jdbc.url=$SONAR_JDBC_URL \
                                 -Dsonar.jdbc.username=$SONAR_JDBC_USERNAME \
                                 -Dsonar.host.url=$SONAR_HOST_URL \
                                 -Dsonar.jacoco.reportPath=$PWD/jacoco-combined.exec \
                                 -Dsonar.jacoco.itReportPath=$PWD/jacoco-combined-it.exec \
                                 -DargLine="$surefire_jvm_args" \
                                 -Dglowroot.shade.skip \
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
                 curl -L http://types.cs.washington.edu/checker-framework/releases/1.9.10/checker-framework-1.9.10.zip > $HOME/checker-framework.zip
                 unzip $HOME/checker-framework.zip -d $HOME
                 # strip version from directory name
                 mv $HOME/checker-framework-* $HOME/checker-framework
                 # need to limit memory of all JVM forks for travis docker build
                 # see https://github.com/travis-ci/travis-ci/issues/3396
                 sed -i 's#/bin/sh#/bin/bash#' $HOME/checker-framework/checker/bin/javac
                 sed -i 's#"java" "-jar" "${mydir}"/../dist/checker.jar "$@"#"java" "-Xmx512m" "-jar" "${mydir}"/../dist/checker.jar -J-Xmx512m "$@" 2>\&1 | tee /tmp/checker.out ; test ${PIPESTATUS[0]} -eq 0#' $HOME/checker-framework/checker/bin/javac
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
               find -name *.java -print0 | xargs -0 sed -i 's|/\*@UnderInitialization\*/|/*@org.checkerframework.checker.initialization.qual.UnderInitialization*/|g'
               find -name *.java -print0 | xargs -0 sed -i 's|/\*@Initialized\*/|/*@org.checkerframework.checker.initialization.qual.Initialized*/|g'
               find -name *.java -print0 | xargs -0 sed -i 's|/\*@Untainted\*/|/*@org.checkerframework.checker.tainting.qual.Untainted*/|g'
               find -name *.java -print0 | xargs -0 sed -i 's|/\*@\([A-Za-z]*\)\*/|/*@org.checkerframework.checker.nullness.qual.\1*/|g'

               # TODO find way to not omit these (especially it-harness)
               # omitting wire-api and agent-parent/it-harness from checker framework validation since they contain protobuf generated code which does not pass
               mvn clean install -am -pl wire-api,agent-parent/it-harness
               # FIXME central is currently omitted due to https://github.com/typetools/checker-framework/issues/529
               mvn clean compile -pl !misc/checker-qual-jdk6,!wire-api,!agent-parent/it-harness,!agent-parent/benchmarks,!agent-parent/ui-sandbox,!central \
                                 -Pchecker \
                                 -Dchecker.install.dir=$HOME/checker-framework \
                                 -Dchecker.stubs.dir=$PWD/misc/checker-stubs \
                                 -Dglowroot.ui.skip \
                                 -DargLine="$surefire_jvm_args" \
                                 -B \
                                 | sed 's/\[ERROR\] .*[\/]\([^\/.]*\.java\):\[\([0-9]*\),\([0-9]*\)\]/[ERROR] (\1:\2) [column \3]/'
               # preserve exit status from mvn (needed because of pipe to sed)
               mvn_status=${PIPESTATUS[0]}
               git checkout -- .
               if [ $mvn_status -ne 0 ]
               then
                 cat /tmp/checker.out
                 exit $mvn_status
               fi
               ;;

  "saucelabs") if [[ $SAUCE_USERNAME && "$TRAVIS_PULL_REQUEST" == "false" ]]
               then
                 mvn clean install -DskipTests=true \
                                   -B
                 cd agent-parent/webdriver-tests
                 mvn clean verify -Dsaucelabs.platform="$SAUCELABS_PLATFORM" \
                                  -Dsaucelabs.browser.name="$SAUCELABS_BROWSER_NAME" \
                                  -Dsaucelabs.browser.version="$SAUCELABS_BROWSER_VERSION" \
                                  -Dsaucelabs.device.name="$SAUCELABS_DEVICE_NAME" \
                                  -Dsaucelabs.device.orientation="$SAUCELABS_DEVICE_ORIENTATION" \
                                  -Dsaucelabs.tunnel.identifier="$TRAVIS_JOB_NUMBER" \
                                  -DargLine="$surefire_jvm_args" \
                                  -B
               else
                 echo skipping, saucelabs only runs against master repository and master branch
               fi
               ;;

esac
