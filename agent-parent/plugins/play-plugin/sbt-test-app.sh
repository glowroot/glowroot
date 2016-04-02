#!/bin/sh

: ${TEST_APP_VERSION:?}
: ${TEST_APP_LANGUAGE:?}

TEST_APP_DIR=$PWD/src/test/app-$TEST_APP_VERSION-$TEST_APP_LANGUAGE

rm -rf tmp-router-files
mkdir tmp-router-files
cd tmp-router-files

mkdir -p app/controllers
mkdir conf
mkdir project

cp $TEST_APP_DIR/scala/controllers/*Controller.scala app/controllers
cp $TEST_APP_DIR/java/controllers/*Controller.java app/controllers
cp -r $TEST_APP_DIR/resources/views app

cp -r $TEST_APP_DIR/resources/public .
cp -r $TEST_APP_DIR/resources/application.conf conf
cp -r $TEST_APP_DIR/resources/routes conf

cp $TEST_APP_DIR/project/build.sbt .
cp $TEST_APP_DIR/project/plugins.sbt project
cp $TEST_APP_DIR/project/build.properties project
cp $TEST_APP_DIR/project/Build.scala project

sbt clean compile

# play 2.4.x - 2.5.x
cp -r target/scala-2.11/routes/main/router $TEST_APP_DIR/scala
cp target/scala-2.11/routes/main/controllers/routes.java $TEST_APP_DIR/java/controllers
cp target/scala-2.11/routes/main/controllers/*.scala $TEST_APP_DIR/scala/controllers
cp -r target/scala-2.11/routes/main/controllers/javascript $TEST_APP_DIR/scala/controllers

# play 2.3.x
cp target/scala-2.11/src_managed/main/controllers/routes.java $TEST_APP_DIR/java/controllers
cp target/scala-2.11/src_managed/main/*.scala $TEST_APP_DIR/scala

# play 2.3.x - 2.5.x
cp -r target/scala-2.11/twirl/main/views $TEST_APP_DIR/scala

# play 2.1.x - 2.2.x
cp target/scala-2.10/src_managed/main/controllers/routes.java $TEST_APP_DIR/java/controllers
cp target/scala-2.10/src_managed/main/*.scala $TEST_APP_DIR/scala
cp -r target/scala-2.10/src_managed/main/views $TEST_APP_DIR/scala


# play 2.0.x
cp target/scala-2.9.1/src_managed/main/controllers/routes.java $TEST_APP_DIR/java/controllers
cp target/scala-2.9.1/src_managed/main/*.scala $TEST_APP_DIR/scala
cp -r target/scala-2.9.1/src_managed/main/views $TEST_APP_DIR/scala

cd ..
