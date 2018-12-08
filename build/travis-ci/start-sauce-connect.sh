#!/bin/bash

# see https://github.com/travis-ci/travis-build/blob/master/lib/travis/build/bash/travis_start_sauce_connect.bash

curl https://saucelabs.com/downloads/sc-4.5.2-linux.tar.gz | tar -zx

sc-4.5.2-linux/bin/sc -i $TRAVIS_JOB_NUMBER -f sauce-connect.ready -d sauce-connect.pid -N &

SAUCE_CONNECT_PID=$!

while test ! -f sauce-connect.ready && ps -f $SAUCE_CONNECT_PID &> /dev/null; do
  sleep .5
done

test -f sauce-connect.ready
