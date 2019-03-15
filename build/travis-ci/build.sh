#!/bin/bash -e

# see https://github.com/travis-ci/travis-ci/issues/8408
_JAVA_OPTIONS=

# java.security.egd is needed for low-entropy docker containers
# /dev/./urandom (as opposed to simply /dev/urandom) is needed prior to Java 8
# (see https://docs.oracle.com/javase/8/docs/technotes/guides/security/enhancements-8.html)
#
# NewRatio is to leave as much memory as possible to old gen
surefire_jvm_args="-Xmx256m -XX:NewRatio=20 -Djava.security.egd=file:/dev/./urandom"
java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
if [[ $java_version == 1.6* || $java_version == 1.7* ]]
then
  # MaxPermSize bump is needed for running grails plugin tests
  surefire_jvm_args="$surefire_jvm_args -XX:MaxPermSize=128m"
fi
if [[ "$TEST_SHADED" == "true" ]]
then
  test_shaded_opt=-Dglowroot.test.shaded
fi

if [[ $java_version == 9* || $java_version == [1-9][0-9]* ]]
then
  cassandra_java_home_opt=-Dcassandra.java.home=/usr/lib/jvm/java-8-openjdk-amd64
fi

# see https://github.com/travis-ci/travis-build/blob/master/lib/travis/build/bash/travis_start_sauce_connect.bash
start_sauce_connect() {
  curl https://saucelabs.com/downloads/sc-4.5.2-linux.tar.gz | tar -zx
  sc-4.5.2-linux/bin/sc -i $TRAVIS_JOB_NUMBER -f sauce-connect.ready -d sauce-connect.pid -N &
  SAUCE_CONNECT_PID=$!
  while test ! -f sauce-connect.ready && ps -f $SAUCE_CONNECT_PID &> /dev/null; do
    sleep .5
  done
  test -f sauce-connect.ready
}

test1_excluded_plugin_modules="!:glowroot-agent-cassandra-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-elasticsearch-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-hibernate-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-http-client-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-jdbc-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-jms-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-jsp-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-kafka-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-logger-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-play-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-quartz-plugin"
test1_excluded_plugin_modules="$test1_excluded_plugin_modules,!:glowroot-agent-redis-plugin"

# these plugins are not excluded in test1:
#   glowroot-agent-ejb-plugin
#   glowroot-agent-executor-plugin
#   glowroot-agent-grails-plugin
#   glowroot-agent-jaxrs-plugin
#   glowroot-agent-jsf-plugin
#   glowroot-agent-netty-plugin
#   glowroot-agent-servlet-plugin
#   glowroot-agent-spring-plugin
#   glowroot-agent-struts-plugin

case "$1" in

      "test1") # excluding :glowroot-agent-ui-sandbox and :glowroot-agent since they depend on plugins which are being excluded
               exclude_modules="$test1_excluded_plugin_modules,!:glowroot-agent-ui-sandbox,!:glowroot-agent"
               activate_profiles="netty-4.x,spring-4.x"
               if [[ $java_version == 1.8* || $java_version == 9* || $java_version == [1-9][0-9]* ]]
               then
                 # these modules are only part of build under Java 8+
                 exclude_modules="$exclude_modules,!:glowroot-central,!:glowroot-webdriver-tests"
                 # mongodb tests use testcontainers which requires Java 8+
                 activate_profiles="$activate_profiles,mongodb-3.7.x"
                 if [[ "$TEST_SHADED" == "true" ]]
                 then
                   # spring-5.1.x profile is cumulative on top of spring-4.x (tests new spring 5 features, e.g. webflux)
                   # latest version of spring requires Java 8+ and the tests require shading since they use netty
                   activate_profiles="$activate_profiles,spring-5.1.x"
                 fi
               fi
               mvn clean install -pl $exclude_modules \
                                 -P $activate_profiles \
                                 -DargLine="$surefire_jvm_args" \
                                 $test_shaded_opt \
                                 -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                 -Dglowroot.ui.skip \
                                 -B
               ;;

      "test2") mvn clean install -DargLine="$surefire_jvm_args" \
                                 -DskipTests \
                                 -Dglowroot.ui.skip \
                                 -B
               if [[ "$TEST_SHADED" == "true" ]]
               then
                 # async-http-client, elasticsearch and play tests all require shading since they use netty
                 if [[ $java_version == 1.6* || $java_version == 1.7* ]]
                 then
                   activate_profiles_opt="-P async-http-client-1.x,elasticsearch-2.x,play-2.2.x,play-2.x"
                 else
                   # latest versions of async-http-client, elasticsearch and play all require Java 8+
                   activate_profiles_opt="-P async-http-client-2.x,elasticsearch-5.x,play-2.4.x,play-2.x"
                 fi
               fi
               # enforcer.skip is needed for async-http-client, elasticsearch and play
               mvn clean install -pl ${test1_excluded_plugin_modules//!:/:} \
                                 $activate_profiles_opt \
                                 -Denforcer.skip \
                                 -DargLine="$surefire_jvm_args" \
                                 $test_shaded_opt \
                                 $cassandra_java_home_opt \
                                 -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                 -B
               mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                -DargLine="$surefire_jvm_args" \
                                $test_shaded_opt \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.jdbcConnectionType=H2 \
                                -B
               mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                -DargLine="$surefire_jvm_args" \
                                $test_shaded_opt \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.jdbcConnectionType=COMMONS_DBCP_WRAPPED \
                                -B
               mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                -DargLine="$surefire_jvm_args" \
                                $test_shaded_opt \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -Dglowroot.test.jdbcConnectionType=TOMCAT_JDBC_POOL_WRAPPED \
                                -B
               if [[ "$GLOWROOT_HARNESS" == "javaagent" ]]
               then
                 # HIKARI_CP_WRAPPED tests using old versions of HikariCP (old versions are needed
                 # in order to test HikariCpProxyHackClassVisitor) only work with javaagent container,
                 # see org.glowroot.agent.plugin.jdbc.Connections#createHikariCpWrappedConnection()
                 mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                  -DargLine="$surefire_jvm_args" \
                                  $test_shaded_opt \
                                  -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                  -Dglowroot.test.jdbcConnectionType=HIKARI_CP_WRAPPED \
                                  -B
                 # GLASSFISH_JDBC_POOL_WRAPPED tests only work with javaagent container because they
                 # depend on weaving bootstrap classes (e.g. java.sql.Statement)
                 mvn clean verify -pl :glowroot-agent-jdbc-plugin \
                                  -DargLine="$surefire_jvm_args" \
                                  $test_shaded_opt \
                                  -Dglowroot.it.harness=javaagent \
                                  -Dglowroot.test.jdbcConnectionType=GLASSFISH_JDBC_POOL_WRAPPED \
                                  -B
                 if [[ "$TEST_SHADED" == "true" ]]
                 then
                   mvn clean verify -pl :glowroot-agent-logger-plugin \
                                    -DargLine="$surefire_jvm_args" \
                                    -Dglowroot.test.shaded \
                                    -Dglowroot.it.harness=javaagent \
                                    -Dglowroot.test.julManager=org.jboss.logmanager.LogManager \
                                    -Dtest.it=JavaLoggingIT \
                                    -B
                 fi
               fi
               ;;

      "test3") if [[ $java_version == 1.6* || $java_version == 1.7* ]]
               then
                 echo test3 target requires Java 8+
                 exit 1
               fi
               mvn clean install -DargLine="$surefire_jvm_args" \
                                 -DskipTests \
                                 -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                 -B

               mvn clean verify -pl :glowroot-central,:glowroot-webdriver-tests \
                                -DargLine="$surefire_jvm_args" \
                                $test_shaded_opt \
                                $cassandra_java_home_opt \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -B
               ;;

      "test4") if [[ $java_version == 1.6* || $java_version == 1.7* ]]
               then
                 echo test4 target requires Java 8+
                 exit 1
               fi
               mvn clean install -DargLine="$surefire_jvm_args" \
                                 -DskipTests \
                                 -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                 -B
               mvn clean verify -pl :glowroot-webdriver-tests \
                                -Dglowroot.internal.webdriver.useCentral=true \
                                -DargLine="$surefire_jvm_args" \
                                $test_shaded_opt \
                                $cassandra_java_home_opt \
                                -Dglowroot.it.harness=$GLOWROOT_HARNESS \
                                -B
               ;;

     "deploy") # build other (non-deployed) modules since many are used by deploy :glowroot-agent-it-harness and :glowroot-agent (below)
               # skipping tests to keep build time consistently under the 50 minute limit (and tests are already run in other jobs)
               # javadoc is needed here since deploy :glowroot-agent attaches the javadoc from :glowroot-agent-core
               mvn clean install -DargLine="$surefire_jvm_args" \
                                 -DskipTests \
                                 -Pjavadoc \
                                 -B
               # only deploy snapshot versions (release versions need pgp signature)
               version=`mvn help:evaluate -Dexpression=project.version | grep -v '\['`
               if [[ "$TRAVIS_REPO_SLUG" == "glowroot/glowroot" && "$TRAVIS_BRANCH" == "master" && "$TRAVIS_PULL_REQUEST" == "false" && "$version" == *-SNAPSHOT ]]
               then
                 mvn clean deploy -pl :glowroot-parent,:glowroot-agent-api,:glowroot-agent-plugin-api,:glowroot-agent-it-harness,:glowroot-agent,:glowroot-central \
                                  -Pjavadoc \
                                  -DargLine="$surefire_jvm_args" \
                                  $test_shaded_opt \
                                  $cassandra_java_home_opt \
                                  -Dglowroot.build.commit=$TRAVIS_COMMIT \
                                  --settings build/travis-ci/settings.xml \
                                  -B
               else
                 mvn clean install -pl :glowroot-parent,:glowroot-agent-api,:glowroot-agent-plugin-api,:glowroot-agent-it-harness,:glowroot-agent,:glowroot-central \
                                   -Pjavadoc \
                                   -DargLine="$surefire_jvm_args" \
                                   $test_shaded_opt \
                                   $cassandra_java_home_opt \
                                   -B
               fi
               ;;

      "sonar") if [[ $SONAR_LOGIN ]]
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
                                 -Dit.test=!JarFileShadingIT \
                                 -DfailIfNoTests=false \
                                 -Djacoco.destFile=$PWD/jacoco-combined.exec \
                                 -Djacoco.propertyName=jacocoArgLine \
                                 -Djacoco.append=true \
                                 -Dglowroot.it.harness=javaagent"
                 # run integration tests
                 mvn $common_mvn_args -P netty-4.x,spring-4.x,mongodb-3.7.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # install to run additional tests
                 mvn clean install -DskipTests \
                                   -B
                 # run webdriver tests against the central collector
                 rm -rf webdriver-tests/cassandra
                 mvn $common_mvn_args -pl :glowroot-webdriver-tests \
                                      -Dglowroot.internal.webdriver.useCentral=true \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 common_mvn_args="$common_mvn_args \
                                  -Dglowroot.test.shaded \
                                  -Denforcer.skip"
                 # elasticsearch 5.x
                 mvn $common_mvn_args -pl agent/plugins/elasticsearch-plugin \
                                      -P elasticsearch-5.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # elasticsearch 2.x
                 mvn $common_mvn_args -pl agent/plugins/elasticsearch-plugin \
                                      -P elasticsearch-2.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # async-http-client 2.x (AsyncHttpClientPluginIT)
                 mvn $common_mvn_args -pl agent/plugins/http-client-plugin \
                                      -P async-http-client-2.x \
                                      -Dit.test=AsyncHttpClientPluginIT \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # async-http-client 1.x (AsyncHttpClientPluginIT)
                 mvn $common_mvn_args -pl agent/plugins/http-client-plugin \
                                      -P async-http-client-1.x \
                                      -Dit.test=AsyncHttpClientPluginIT \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # okhttp prior to 2.2.0 (OkHttpClientPluginIT)
                 mvn $common_mvn_args -pl agent/plugins/http-client-plugin \
                                      -Dokhttpclient2x.version=2.1.0 \
                                      -Dit.test=OkHttpClientPluginIT \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # LogbackIT prior to 0.9.16
                 mvn $common_mvn_args -pl agent/plugins/logger-plugin \
                                      -P logback-old \
                                      -Dit.test=LogbackIT \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # netty 3.x
                 mvn $common_mvn_args -pl agent/plugins/netty-plugin \
                                      -P netty-3.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # play 2.4.x
                 mvn $common_mvn_args -pl agent/plugins/play-plugin \
                                      -P play-2.4.x,play-2.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # play 2.2.x
                 mvn $common_mvn_args -pl agent/plugins/play-plugin \
                                      -P play-2.2.x,play-2.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # TODO Play 2.0.x and 2.1.x require Java 7
                 # play 1.x
                 mvn $common_mvn_args -pl agent/plugins/play-plugin \
                                      -P play-1.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # spring 5.1.x
                 mvn $common_mvn_args -pl agent/plugins/spring-plugin \
                                      -P spring-5.1.x,spring-4.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # spring 3.2.x
                 mvn $common_mvn_args -pl agent/plugins/spring-plugin \
                                      -P spring-3.2.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # spring 3.x
                 mvn $common_mvn_args -pl agent/plugins/spring-plugin \
                                      -P spring-3.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # mongodb pre-3.7.x
                 mvn $common_mvn_args -pl agent/plugins/mongodb-plugin \
                                      -P mongodb-pre-3.7.x \
                                      -DargLine="$surefire_jvm_args \${jacocoArgLine}" \
                                      -B
                 # the sonar.login system property is set in the pom.xml using the
                 # environment variable SONAR_LOGIN (instead of setting the system
                 # property on the command line which which would make it visible to ps)
                 mvn clean verify sonar:sonar -pl !build/license-bundle,!build/checker-jdk6,!build/error-prone-jdk6,!build/multi-lib-tests,!agent/shaded/embedded,!agent/shaded/core,!agent/shaded/it-harness,!agent/shaded/central-https-linux,!agent/shaded/central-https-windows,!agent/shaded/central-https-osx,!agent/benchmarks,!agent/ui-sandbox,!agent/dist-maven-plugin,!agent/dist \
                                   -Dsonar.host.url=https://sonarcloud.io \
                                   -Dsonar.organization=glowroot \
                                   -Dsonar.jacoco.reportPaths=$PWD/jacoco-combined.exec \
                                   -DargLine="$surefire_jvm_args" \
                                   -B
               else
                 echo SONAR_LOGIN token missing
               fi
               ;;

    "checker") set +e
               git diff --exit-code > /dev/null
               if [ $? -ne 0 ]
               then
                 echo you have unstaged changes!
                 exit 1
               fi
               set -e

               echo "MAVEN_OPTS=\"-Xmx1g -XX:NewRatio=20\"" > ~/.mavenrc

               find -name *.java -print0 | xargs -0 sed -i 's|/\*@UnderInitialization\*/|@org.checkerframework.checker.initialization.qual.UnderInitialization|g'
               find -name *.java -print0 | xargs -0 sed -i 's|/\*@Initialized\*/|@org.checkerframework.checker.initialization.qual.Initialized|g'
               find -name *.java -print0 | xargs -0 sed -i 's|/\*@Untainted\*/|@org.checkerframework.checker.tainting.qual.Untainted|g'
               find -name *.java -print0 | xargs -0 sed -i 's|/\*@\([A-Za-z]*\)\*/|@org.checkerframework.checker.nullness.qual.\1|g'
               find agent/plugin-api -name *.java -print0 | xargs -0 sed -i 's|^import org.glowroot.agent.plugin.api.checker.|import org.checkerframework.checker.nullness.qual.|g'
               find agent/plugins -name *.java -print0 | xargs -0 sed -i 's|^import org.glowroot.agent.plugin.api.checker.|import org.checkerframework.checker.nullness.qual.|g'

               # omitting wire-api from checker framework validation since it contains large protobuf generated code which does not pass
               # and even when using -AskipDefs, checker framework still runs on the code (even though it does not report errors)
               # and it is so slow that it times out the travis ci build
               mvn clean install -am -pl wire-api \
                                 -Dglowroot.ui.skip \
                                 -DskipTests \
                                 -B
               # this is just to keep travis ci build from timing out due to "No output has been received in the last 10 minutes, ..."
               while true; do sleep 60; echo ...; done &
               mvn clean compile -pl !build/checker-jdk6,!wire-api,!agent/benchmarks,!agent/ui-sandbox,!agent/dist \
                                 -Dglowroot.checker.build \
                                 -Dchecker.stubs.dir=$PWD/build/checker-stubs \
                                 -Dglowroot.ui.skip \
                                 -B \
                                 | sed 's/\[ERROR\] .*[\/]\([^\/.]*\.java\):\[\([0-9]*\),\([0-9]*\)\]/[ERROR] (\1:\2) [column \3]/'
               # preserve exit status from mvn (needed because of pipe to sed)
               mvn_status=${PIPESTATUS[0]}
               git checkout -- .
               exit $mvn_status
               ;;

 "saucelabs1") if [[ $SAUCE_USERNAME && "$TRAVIS_PULL_REQUEST" == "false" ]]
               then
                 start_sauce_connect
                 mvn clean install -DskipTests \
                                   -B
                 cd webdriver-tests
                 # this is just to keep travis ci build from timing out due to "No output has been received in the last 10 minutes, ..."
                 while true; do sleep 60; echo ...; done &
                 mvn clean verify -Dit.test=BasicSmokeIT,AdminIT \
                                  -Dsaucelabs.platform="$SAUCELABS_PLATFORM" \
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

 "saucelabs2") if [[ $SAUCE_USERNAME && "$TRAVIS_PULL_REQUEST" == "false" ]]
               then
                 start_sauce_connect
                 mvn clean install -DskipTests \
                                   -B
                 cd webdriver-tests
                 # this is just to keep travis ci build from timing out due to "No output has been received in the last 10 minutes, ..."
                 while true; do sleep 60; echo ...; done &
                 mvn clean verify -Dit.test=AlertConfigIT,InstrumentationConfigIT \
                                  -Dsaucelabs.platform="$SAUCELABS_PLATFORM" \
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

 "saucelabs3") if [[ $SAUCE_USERNAME && "$TRAVIS_PULL_REQUEST" == "false" ]]
               then
                 start_sauce_connect
                 mvn clean install -DskipTests \
                                   -B
                 cd webdriver-tests
                 # this is just to keep travis ci build from timing out due to "No output has been received in the last 10 minutes, ..."
                 while true; do sleep 60; echo ...; done &
                 mvn clean verify -Dit.test=!BasicSmokeIT,!AdminIT,!AlertConfigIT,!InstrumentationConfigIT \
                                  -Dsaucelabs.platform="$SAUCELABS_PLATFORM" \
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
