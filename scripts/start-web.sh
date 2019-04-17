#!/usr/bin/env bash

SCRIPTS_DIR=$(dirname "$0")

echo "SCRIPTS_DIR is: $SCRIPTS_DIR"

pushd $SCRIPTS_DIR/..

echo "pwd is: `pwd`"
cd ..
sbt web/run

popd

