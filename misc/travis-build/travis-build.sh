#!/bin/bash -e

# java.security.egd is needed for low-entropy docker containers
# /dev/./urandom (as opposed to simply /dev/urandom) is needed prior to Java 8
# (see https://docs.oracle.com/javase/8/docs/technotes/guides/security/enhancements-8.html)
#
# NewRatio is to leave as much memory as possible to old gen
surefire_jvm_args="-Xmx256m -XX:NewRatio=20 -Djava.security.egd=file:/dev/./urandom"
java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
if [[ "$java_version" < "1.8" ]]
then
  # MaxPermSize bump is needed for running grails plugin tests
  surefire_jvm_args="$surefire_jvm_args -XX:MaxPermSize=128m"
fi

case "$1" in

       "test") if [[ "$SKIP_SHADING" == "true" ]]
               then
                 skip_shading_opt=-Dglowroot.shade.skip
               fi
               mvn clean install -DargLine="$surefire_jvm_args" \
                                 $skip_shading_opt \
                                 -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                 -Dglowroot.test.fileLoggingOnly=false \
                                 -B
               if [[ "$java_version" > "1.8" && "$SKIP_SHADING" == "true" ]]
               then
                 # glowroot server requires java 8+
                 # and needs to run against unshaded it-harness to avoid shading complications
                 mvn clean verify -pl :glowroot-webdriver-tests \
                                  -Dglowroot.internal.webdriver.server=true \
                                  -DargLine="$surefire_jvm_args" \
                                  -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                  -Dglowroot.test.fileLoggingOnly=false \
                                  -B
               fi
               mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                -DargLine="$surefire_jvm_args" \
                                $skip_shading_opt \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.fileLoggingOnly=false \
                                -Dglowroot.test.jdbcConnectionType=H2 \
                                -B
               mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                -DargLine="$surefire_jvm_args" \
                                $skip_shading_opt \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.fileLoggingOnly=false \
                                -Dglowroot.test.jdbcConnectionType=COMMONS_DBCP_WRAPPED \
                                -B
               mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                -DargLine="$surefire_jvm_args" \
                                $skip_shading_opt \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.fileLoggingOnly=false \
                                -Dglowroot.test.jdbcConnectionType=TOMCAT_JDBC_POOL_WRAPPED \
                                -B
               ;;

     "deploy") # build shaded distribution zip which will be uploaded to s3 in travis-ci deploy step
               mvn clean install -Pjavadoc \
                                 -DargLine="$surefire_jvm_args" \
                                 -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                 -Dglowroot.test.fileLoggingOnly=false \
                                 -B
               # only deploy snapshot versions (release versions need pgp signature)
               version=`mvn help:evaluate -Dexpression=project.version | grep -v '\['`
               if [[ "$TRAVIS_REPO_SLUG" == "glowroot/glowroot" && "$TRAVIS_BRANCH" == "master" && "$TRAVIS_PULL_REQUEST" == "false" && "$version" == *-SNAPSHOT ]]
               then
                 # deploy only glowroot-parent, glowroot-agent-api, glowroot-agent-plugin-api, glowroot-agent and glowroot-agent-it-harness artifacts to maven repository
                 mvn clean deploy -pl :glowroot-parent,:glowroot-agent-api,:glowroot-agent-plugin-api,:glowroot-agent,:glowroot-agent-it-harness \
                                  -Pjavadoc \
                                  -DargLine="$surefire_jvm_args" \
                                  -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                  -Dglowroot.test.fileLoggingOnly=false \
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
                 mvn clean org.jacoco:jacoco-maven-plugin:prepare-agent test \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -Djacoco.propertyName=jacocoArgLine \
                                 -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                 -Dglowroot.shade.skip \
                                 -Dglowroot.test.fileLoggingOnly=false \
                                 -B
                 # intentionally calling failsafe plugin directly in order to skip surefire (unit test) execution
                 #
                 # code coverage for @Pointcut classes are only captured when run with javaagent
                 # integration test harness since in that case jacoco javaagent goes first
                 # (see JavaagentMain) and uses the original bytecode to construct the class ids,
                 # whereas when run with local integration test harness jacoco javaagent uses the
                 # bytecode that is woven by IsolatedWeavingClassLoader to construct the class ids
                 common_mvn_args="clean \
                                 org.jacoco:jacoco-maven-plugin:prepare-agent-integration \
                                 test-compile \
                                 failsafe:integration-test \
                                 failsafe:verify \
                                 -Djacoco.destFile=$PWD/jacoco-combined-it.exec \
                                 -Djacoco.propertyName=jacocoArgLine \
                                 -Djacoco.append=true \
                                 -Dglowroot.shade.skip \
                                 -Dglowroot.it.harness=javaagent \
                                 -Dglowroot.test.fileLoggingOnly=false"
                 # run integration tests
                 mvn $common_mvn_args -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # install unshaded to run webdriver tests against server
                 mvn clean install -Dglowroot.shade.skip -DskipTests -B
                 # run webdriver tests against server
                 mvn $common_mvn_args -pl :glowroot-webdriver-tests \
                                      -Dglowroot.internal.webdriver.server=true \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # install shaded in order to run certain plugin tests
                 mvn clean install -DskipTests -B
                 # enforcer.skip is needed for remaining tests
                 common_mvn_args="$common_mvn_args \
                                  -Denforcer.skip \
                                  \"-DargLine=$surefire_jvm_args \\\${jacocoArgLine}\""
                 # async-http-client 2.x (AsyncHttpClientPluginIT)
                 mvn $common_mvn_args -pl agent-parent/plugins/http-client-plugin \
                                      -P async-http-client-2.x \
                                      -Dit.test=AsyncHttpClientPluginIT \
                                      -B
                 # async-http-client 1.x (AsyncHttpClientPluginIT)
                 mvn $common_mvn_args -pl agent-parent/plugins/http-client-plugin \
                                      -P async-http-client-1.x \
                                      -Dit.test=AsyncHttpClientPluginIT \
                                      -B
                 # okhttp prior to 2.2.0 (OkHttpClientPluginIT)
                 mvn $common_mvn_args -pl agent-parent/plugins/http-client-plugin \
                                      -Dokhttpclient.version=2.1.0 \
                                      -Dit.test=OkHttpClientPluginIT \
                                      -B
                 # LogbackIT (only runs against shaded agent)
                 mvn $common_mvn_args -pl agent-parent/plugins/logger-plugin \
                                      -Dit.test=LogbackIT \
                                      -B
                 # LogbackIT prior to 0.9.16
                 mvn $common_mvn_args -pl agent-parent/plugins/logger-plugin \
                                      -P logback-old \
                                      -Dit.test=LogbackIT \
                                      -B
                 # netty 3.x
                 mvn $common_mvn_args -pl agent-parent/plugins/netty-plugin \
                                      -P netty-3.x \
                                      -B
                 # play 2.2.x
                 mvn $common_mvn_args -pl agent-parent/plugins/play-plugin \
                                      -P play-2.2.x,play-2.x \
                                      -B
                 # TODO Play 2.0.x and 2.1.x require Java 7
                 # play 1.x
                 mvn $common_mvn_args -pl agent-parent/plugins/play-plugin \
                                      -P play-1.x \
                                      -B
                 # the sonar.jdbc.password system property is set in the pom.xml using the
                 # environment variable SONAR_DB_PASSWORD (instead of setting the system
                 # property on the command line which which would make it visible to ps)
                 mvn clean verify sonar:sonar -pl !misc/license-resource-bundle,!misc/checker-qual-jdk6,!misc/multi-lib-version-tester,!agent-parent/benchmarks,!agent-parent/ui-sandbox,!agent-parent/dist-maven-plugin,!agent-parent/distribution \
                                   -Dsonar.jdbc.url=$SONAR_JDBC_URL \
                                   -Dsonar.jdbc.username=$SONAR_JDBC_USERNAME \
                                   -Dsonar.host.url=$SONAR_HOST_URL \
                                   -Dsonar.jacoco.reportPath=$PWD/jacoco-combined.exec \
                                   -Dsonar.jacoco.itReportPath=$PWD/jacoco-combined-it.exec \
                                   -DargLine="$surefire_jvm_args" \
                                   -Dglowroot.shade.skip \
                                   -Dglowroot.test.fileLoggingOnly=false \
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
                 curl -L http://types.cs.washington.edu/checker-framework/releases/2.0.0/checker-framework-2.0.0.zip > $HOME/checker-framework.zip
                 unzip $HOME/checker-framework.zip -d $HOME
                 # strip version from directory name
                 mv $HOME/checker-framework-* $HOME/checker-framework
                 # need to limit memory of all JVM forks for travis docker build
                 # see https://github.com/travis-ci/travis-ci/issues/3396
                 sed -i 's#/bin/sh#/bin/bash#' $HOME/checker-framework/checker/bin/javac
                 sed -i 's#"java" "-jar" "${mydir}"/../dist/checker.jar ${args}#"java" "-Xmx512m" "-jar" "${mydir}"/../dist/checker.jar -J-Xmx512m ${args} 2>\&1 | tee /tmp/checker.out ; test ${PIPESTATUS[0]} -eq 0#' $HOME/checker-framework/checker/bin/javac
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
               mvn clean install -am -pl wire-api,agent-parent/it-harness \
                                 -Dglowroot.ui.skip \
                                 -DskipTests \
                                 -B
               mvn clean compile -pl !misc/checker-qual-jdk6,!wire-api,!agent-parent/it-harness,!agent-parent/benchmarks,!agent-parent/ui-sandbox,!agent-parent/distribution \
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
                 mvn clean install -DskipTests \
                                   -B
                 cd webdriver-tests
                 mvn clean verify -Dsaucelabs.platform="$SAUCELABS_PLATFORM" \
                                  -Dsaucelabs.browser.name="$SAUCELABS_BROWSER_NAME" \
                                  -Dsaucelabs.browser.version="$SAUCELABS_BROWSER_VERSION" \
                                  -Dsaucelabs.device.name="$SAUCELABS_DEVICE_NAME" \
                                  -Dsaucelabs.device.orientation="$SAUCELABS_DEVICE_ORIENTATION" \
                                  -Dsaucelabs.tunnel.identifier="$TRAVIS_JOB_NUMBER" \
                                  -DargLine="$surefire_jvm_args" \
                                  -Dglowroot.test.fileLoggingOnly=false \
                                  -B
               else
                 echo skipping, saucelabs only runs against master repository and master branch
               fi
               ;;

esac
