#!/bin/bash

# see https://github.com/travis-ci/travis-build/blob/master/lib/travis/build/bash/travis_stop_sauce_connect.bash

pkill -F sauce-connect.pid

for i in 0 1 2 3 4 5 6 7 8 9; do
  if pkill -0 -F sauce-connect.pid &> /dev/null; then
    echo Waiting for graceful Sauce Connect shutdown $((i + 1))/10
    sleep 1
  else
    echo Sauce Connect shutdown complete
    exit 0
  fi
done

if pkill -0 -F sauce-connect.pid &> /dev/null; then
  pkill -9  -F sauce-connect.pid &> /dev/null || true
fi
