#!/usr/bin/env bash
# Downloads real Avro schemas (.avsc) and protocols (.avpr) from the apache/avro repository into samples/.
#   ./fetch-samples.sh
set -u
cd "$(dirname "$0")"
mkdir -p samples
BASE="https://raw.githubusercontent.com/apache/avro/main"
FILES="
share/test/schemas/BulkData.avpr
share/test/schemas/FooBarSpecificRecord.avsc
share/test/schemas/RecordWithRequiredFields.avsc
share/test/schemas/fooBar.avsc
share/test/schemas/interop.avsc
share/test/schemas/mail.avpr
share/test/schemas/namespace.avpr
share/test/schemas/reserved.avsc
share/test/schemas/simple.avpr
share/test/schemas/weather.avsc
share/schemas/org/apache/avro/ipc/HandshakeRequest.avsc
share/schemas/org/apache/avro/ipc/HandshakeResponse.avsc
doc/examples/user.avsc
lang/java/avro/src/test/resources/SchemaBuilder.avsc
lang/java/avro/src/test/resources/TestRecordWithLogicalTypes.avsc
lang/java/avro/src/test/resources/TestRecordWithMapsAndArrays.avsc
lang/java/avro/src/test/resources/TestUnionRecord.avsc
"
# The page of a file in its repository, for a reader: raw.githubusercontent.com gives the bare text.
page() {
    case "$1" in
        https://raw.githubusercontent.com/*)
            local p="${1#https://raw.githubusercontent.com/}"
            local owner="${p%%/*}"; p="${p#*/}"
            local repo="${p%%/*}"; p="${p#*/}"
            echo "https://github.com/$owner/$repo/blob/$p" ;;
        *) echo "$1" ;;
    esac
}
{
    echo "# Where every sample comes from: <path in samples/> <page of the original>. Written by fetch-samples.sh;"
    echo "# the converter links these pages in the headers of the descriptions."
    for f in $FILES; do printf '%-32s %s\n' "$(basename "$f")" "$(page "$BASE/$f")"; done
} > samples/sources.txt
status=0
for f in $FILES; do
    n="$(basename "$f")"
    if curl -sSf -A 'Mozilla/5.0' -o "samples/$n" "$BASE/$f"; then echo "ok      $n"; else echo "FAILED  $f"; status=1; fi
done
exit $status
