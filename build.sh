#!/usr/bin/env bash
# Compiles the converter and converts every sample into AdHoc/.
#   ./build.sh            # build + convert samples/ → AdHoc/
set -eu
cd "$(dirname "$0")"
rm -rf out
mkdir -p out AdHoc
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.Avro2AdHoc samples AdHoc
