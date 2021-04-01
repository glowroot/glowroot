#!/bin/bash

# see https://github.com/travis-ci/travis-build/blob/master/lib/travis/build/bash/travis_stop_sauce_connect.bash

pkill -F sauce-connect.pid

for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  if pkill -0 -F sauce-connect.pid &> /dev/null; then
    echo waiting for graceful sauce connect shutdown $i/15
    sleep 1
  else
    echo sauce connect shutdown complete
    exit 0
  fi
done

if pkill -0 -F sauce-connect.pid &> /dev/null; then
  echo forcefully terminating sauce connect
  pkill -9 -F sauce-connect.pid &> /dev/null || true
fi
