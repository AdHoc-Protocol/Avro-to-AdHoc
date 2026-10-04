# Avro-to-AdHoc - Apache Avro schemas → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Translates [Apache Avro](https://avro.apache.org/) schemas (`.avsc`) and protocols (`.avpr`) into
[AdHoc](https://github.com/AdHoc-Protocol) protocol-description `.cs` files, one per input file, ready for
AdHocAgent. The project is self-contained: a single Java class plus a local copy of the emitter helpers
(`src/org/unirail/adhoc/`), no external dependencies (JSON is read by the bundled `Json.java`).

Every shipped sample validates with AdHocAgent's local parse-only mode (17 of 17 files `OK`, see below).

## Before and after

[`mail.avpr`](https://github.com/apache/avro/blob/main/share/test/schemas/mail.avpr), 26 lines - short because no upstream `.avsc` or `.avpr` in apache/avro approaches 500 lines, and
quoted **complete, nothing elided**: [source](https://github.com/apache/avro/blob/main/share/test/schemas/mail.avpr) → [result](AdHoc/mail.cs). A `Message` record and
two protocol messages: `send`, which expects a response, and `fireandforget`, marked `"one-way": true`.

```json
{"namespace": "org.apache.avro.test",
 "protocol": "Mail",

 "types": [
     {"name": "Message", "type": "record",
      "fields": [
          {"name": "to",   "type": "string"},
          {"name": "from", "type": "string"},
          {"name": "body", "type": "string"}
      ]
     }
 ],

 "messages": {
     "send": {
         "request": [{"name": "message", "type": "Message"}],
         "response": "string"
     },
     "fireandforget": {
         "request": [{"name": "message", "type": "Message"}],
         "response": "null",
         "one-way": true
     }

 }
}
```

```csharp
        class Message {
            string to;
            string From;
            string body;
        }
        // …
        class send_Request {
            Message message;
        }

        /**
        Response of message send
        */
        class send_Response { string value; }
        // …
        class fireandforget_Request {
            Message message;
        }

        interface Connection : Connects<Client, Server> {
            (L____________, send_Response) send(send_Request req);
            // one-way messages: fire-and-forget from the client
            [l____________<fireandforget_Request>]
            struct OneWay { }
        }
```

The request/response pair became an **RPC method** - the Client calls, the Server answers - while the one-way
message became a **non-transitional branch** that changes no state. A `string` response is not a record, so it
travels in a generated `send_Response` wrapper; `from` is capitalised because it is a TypeScript keyword.

Each Avro union became a pack whose alternatives are all optional, the inline record was lifted to a named type,
the `int` alternative carries `[X]` because Avro zigzags it, and the JSON defaults survive as metadata.

## Links

| What                                   | Where                                                                                                          |
|:---------------------------------------|:---------------------------------------------------------------------------------------------------------------|
| Avro specification (schemas, protocols, logical types) | https://avro.apache.org/docs/current/specification/                                                  |
| Avro repository (source of every sample)               | https://github.com/apache/avro                                                                       |
| Sample schemas / protocols                             | https://github.com/apache/avro/tree/main/share/test/schemas                                          |
| IPC handshake schemas                                  | https://github.com/apache/avro/tree/main/share/schemas/org/apache/avro/ipc                            |
| Getting-started schema                                 | https://github.com/apache/avro/blob/main/doc/examples/user.avsc                                      |
| Java test schemas (logical types, maps/arrays, unions) | https://github.com/apache/avro/tree/main/lang/java/avro/src/test/resources                           |
| AdHoc protocol description format                      | https://github.com/AdHoc-Protocol/AdHoc-protocol (README of AdHocAgent)                              |

[`samples/sources.txt`](samples/sources.txt) gives the original page of every sample file, and the header of every
description links the original it was made from.

## Layout

| Path                                | Contents                                                                 |
|:------------------------------------|:-------------------------------------------------------------------------|
| `src/org/unirail/Avro2AdHoc.java`   | The converter                                                            |
| `src/org/unirail/adhoc/*.java`      | Local copy of the AdHoc emitter helpers and the JSON reader              |
| `src/org/unirail/adhoc/Originals.java` | Reads `sources.txt`: the original page of an input file, for the header of its description |
| `fetch-samples.sh`                  | Downloads the 17 upstream sample files into `samples/` and writes `samples/sources.txt` |
| `samples/`                          | 13 `.avsc` + 4 `.avpr` from apache/avro, and `sources.txt`, the original page of each |
| `build.sh`                          | Compiles and converts `samples/` → `AdHoc/`                               |
| `validate.sh`                       | Runs AdHocAgent (parse-only) over `AdHoc/*.cs`                            |
| `AdHoc/`                            | Generated descriptors                                                     |

## Build, run, validate

Java 17+ and (for validation) a built AdHocAgent (`AdHocAgent.exe` (found on `PATH`, or set `AGENT=/path/to/AdHocAgent.exe`), override with `AGENT=…`).

```bash
./fetch-samples.sh                     # samples/ ← apache/avro
./build.sh                             # javac + java … Avro2AdHoc samples AdHoc
./validate.sh AdHoc                    # every file must print OK

# any other input:
java -Dfile.encoding=UTF-8 -cp out org.unirail.Avro2AdHoc <file.avsc | file.avpr | folder> [output folder]
```

Output: `namespace org.avro { public interface <file name> { … } }`; the project interface gets a `Schema` suffix when
a schema type carries the file's name (C# forbids a nested type named like its enclosing type).

## Mapping

| Avro                                   | AdHoc                                                           | Notes                                                                                  |
|:---------------------------------------|:----------------------------------------------------------------|:---------------------------------------------------------------------------------------|
| `record`, `error`                      | `class Name { … }`                                              | Doc + `Avro record: <full name>` line; simple name when unique in the file, else the full name flattened |
| `enum` (≥ 2 symbols)                   | `enum Name { A, B, … }`                                         | Symbols that are keywords of a target language get a capital letter (`default` → `Default`) |
| `enum` (< 2 symbols)                   | `public struct Name { const int A = 0; }`                       | AdHoc rejects enums with fewer than two members; referencing fields become `int` + `[EnumRef]` |
| `fixed(N)`                             | `class Name { [D(N)] Binary[] TYPEDEF; }`                       | A type alias; fields use `Name`                                                        |
| `int`, `long`                          | **`[X] int`**, **`[X] long`**                                   | Avro's authors chose **zigzag varint** for these, which is a statement that the values are two-sided and cluster around zero. `[X]` says the same thing on the AdHoc wire, and a signed type's declared range does not widen. `[X]` is not free - see [the physics of a number](#the-physics-of-a-number) |
| `float`, `double`                      | `float double`                                                  | Not integers, so `[A]` / `[V]` / `[X]` do not apply to them at all                      |
| `boolean`                              | `bool`                                                          | A one-byte type has no leading zero groups for a varint to drop                         |
| `string`                               | `string`                                                        |                                                                                        |
| `bytes`                                | `Binary[,,]`                                                    | Variable-length byte list                                                              |
| `null` (as a field type)               | no field, a comment                                             | Carries no data                                                                        |
| `array<T>`                             | `T[,,]`                                                         | Nested collections nest natively: `string[,,][,,]`, `Map<string, long>[,,]`. An attribute on an array applies to its elements |
| `map<T>`                               | `Map<string, T>`                                                | Avro map keys are always `string`, so value attributes carry the target: `[Val: X] Map<string, long>`. A map directly inside a map is wrapped in a one-field sub-pack |
| `["null", T]` / `[T, "null"]`          | `T?` for value types; `T` for reference types                   | Reference-typed fields are optional in AdHoc anyway; inside collections items get `?`  |
| union of several non-null types        | `class <Record>_<field>_union { T1? …; T2 …; }`                 | One optional field per alternative, named after the alternative type (AdHoc "Object type" guidance) |
| `"default": v`                         | `[Default(v)]` / `[DefaultNull]`                                | JSON scalars keep their type, complex defaults are JSON text                            |
| `timestamp-millis/micros/nanos`, `local-timestamp-*` | **`DateTime`**                                    | A wall-clock instant is a first-class AdHoc type; the raw integer never reaches the generated API |
| `date`                                 | **`class DaysSinceEpoch : DateTimeDef`**                        | `min => 1970-01-01`, `precision => TimeSpan.FromDays(1)`; AdHoc allocates only the bits the range needs |
| `time-millis`                          | **`class TimeOfDayMillis : Duration`**                          | `max => 86_400_000`, 1 ms precision: elapsed time since midnight                        |
| `time-micros`                          | **`class TimeOfDayMicros : Duration`**                          | Same, but AdHoc's `Duration` precision floor is 1 ms, so sub-millisecond resolution is dropped - a comment marks it in the generated file |
| `decimal`, `uuid`, `duration`, others  | base type + `[LogicalType("name")]`                              | AdHoc has no concept for these, so they stay metadata; `decimal` keeps precision and scale: `[LogicalType("decimal", 31, 8)]` |
| `"order": "descending"/"ignore"`       | `[Order("…")]`                                                  |                                                                                        |
| field `aliases`                        | `[Aliases("a,b")]`                                              | Type aliases go into the doc line                                                      |
| unknown type name                      | `Binary[,,]` + `[Unresolved("name")]`                            | Warning on stderr                                                                      |
| protocol `messages` (two-way)          | `(L____________, Response, Error…) name(name_Request req);`      | RPC shorthand: Client calls, Server answers. Request arguments → `name_Request` pack; a record response is used directly, a primitive/collection response is wrapped in `name_Response { T value; }`, a `null` response becomes an empty `name_Response` acknowledgement; `errors` become extra response packs |
| protocol `messages` (`"one-way": true`) | `[l____________<(a_Request, b_Request)>] struct OneWay { }`     | Fire-and-forget from the Client                                                        |
| `.avsc` top-level record(s)            | `[_____lr_____<(A, B)>] struct Exchange { }`                     | Either host may send any top-level record; nested records travel as sub-packs          |
| enums referenced by no field           | `interface X : _<(X.E1, X.E2)>`                                  | Pushed to every host, otherwise they would reach no generated code                     |
| schema with nothing to send            | `class NoArg { }` in the `Exchange` state                       | Gives the connection a state with something to transmit                                |

Collections and strings are unbounded in Avro while AdHoc caps every collection at 255 by default, so each
generated file carries one `_DefaultMaxLengthOf` enum raising arrays, maps, sets and strings to 65 535. Edit that
enum, or add `[D(N)]` / `[D(+N)]` per field, to match the real payloads.

Hosts are `Client` and `Server` (both request all six generators); the connection is `Connection`.

Anything the converter could not express is marked by a comment at the place it was dropped, not only here: the
`null` field type, the `time-micros` precision floor, the map-inside-a-map wrapper and unresolved type names all
leave a line in the generated file for whoever refines it.

### The physics of a number

`[X]` is emitted on every `int` and `long` because Avro's own encoding says the values are two-sided and small.
That is a statement about the *schema*, not about any particular field, and varint is not free: it wins only while
the typical distance from the base stays under about two million, and past **268 435 455** it always costs an
extra byte. A millisecond timestamp, a monotonic id, a hash and a coordinate scaled by 1e7 are all varint losses.

Avro has no way to say which of its `long` fields is which, so the converter does not guess - it asks, on the
field, where the person refining the file will see it:

This is `weather.time` as the converter actually emits it into [AdHoc/weather.cs](AdHoc/weather.cs):

```csharp
            [X] long time; // physics: values look systematically large (a timestamp, hash or scaled coordinate) and varint loses past 268 435 455 - [X] likely costs a byte here, measure and drop it if so
```

A field whose name or doc reads like a non-negative counter gets the opposite hint, since zigzag spends a bit on a
sign that never appears - `… // physics: a non-negative counter clustering at its floor → consider [A(0)] instead
of [X]`. No attribute is ever invented from a guess; only the question is raised.

## Validation result

All 17 generated descriptors pass AdHocAgent's local parse-only validation - exit code 0, no errors, no warnings:

```
$ ./validate.sh AdHoc
BulkData                         OK
FooBarSpecificRecord             OK
HandshakeRequest                 OK
HandshakeResponse                OK
RecordWithRequiredFields         OK
SchemaBuilder                    OK
TestRecordWithLogicalTypes       OK
TestRecordWithMapsAndArrays      OK
TestUnionRecord                  OK
fooBar                           OK
interop                          OK
mail                             OK
namespace                        OK
reserved                         OK
simple                           OK
user                             OK
weather                          OK
$ echo $?
0
```

The originals of these files:
[`BulkData.avpr`](https://github.com/apache/avro/blob/main/share/test/schemas/BulkData.avpr),
[`FooBarSpecificRecord.avsc`](https://github.com/apache/avro/blob/main/share/test/schemas/FooBarSpecificRecord.avsc),
[`HandshakeRequest.avsc`](https://github.com/apache/avro/blob/main/share/schemas/org/apache/avro/ipc/HandshakeRequest.avsc),
[`HandshakeResponse.avsc`](https://github.com/apache/avro/blob/main/share/schemas/org/apache/avro/ipc/HandshakeResponse.avsc),
[`RecordWithRequiredFields.avsc`](https://github.com/apache/avro/blob/main/share/test/schemas/RecordWithRequiredFields.avsc),
[`SchemaBuilder.avsc`](https://github.com/apache/avro/blob/main/lang/java/avro/src/test/resources/SchemaBuilder.avsc),
[`TestRecordWithLogicalTypes.avsc`](https://github.com/apache/avro/blob/main/lang/java/avro/src/test/resources/TestRecordWithLogicalTypes.avsc),
[`TestRecordWithMapsAndArrays.avsc`](https://github.com/apache/avro/blob/main/lang/java/avro/src/test/resources/TestRecordWithMapsAndArrays.avsc),
[`TestUnionRecord.avsc`](https://github.com/apache/avro/blob/main/lang/java/avro/src/test/resources/TestUnionRecord.avsc),
[`fooBar.avsc`](https://github.com/apache/avro/blob/main/share/test/schemas/fooBar.avsc),
[`interop.avsc`](https://github.com/apache/avro/blob/main/share/test/schemas/interop.avsc),
[`mail.avpr`](https://github.com/apache/avro/blob/main/share/test/schemas/mail.avpr),
[`namespace.avpr`](https://github.com/apache/avro/blob/main/share/test/schemas/namespace.avpr),
[`reserved.avsc`](https://github.com/apache/avro/blob/main/share/test/schemas/reserved.avsc),
[`simple.avpr`](https://github.com/apache/avro/blob/main/share/test/schemas/simple.avpr),
[`user.avsc`](https://github.com/apache/avro/blob/main/doc/examples/user.avsc),
[`weather.avsc`](https://github.com/apache/avro/blob/main/share/test/schemas/weather.avsc).

## Limitations

- `.avdl` (Avro IDL) is not read; compile it to `.avpr` with `avro-tools idl` first.
- Named-type references are resolved by full name, then by unique simple name within the file; a name used before
  its definition in another file is reported as unresolved (each file is converted on its own).
- Avro `map` keys are always `string`; a map that is the value of another map travels in a wrapper sub-pack.
- Custom schema properties (`javaAnnotation`, `customProp`, …) are ignored.
- `time-micros` loses sub-millisecond resolution: AdHoc's `Duration` precision floor is 1 ms.
- `decimal`, `uuid` and the `duration` logical type stay raw bytes plus a `[LogicalType]` attribute - AdHoc models
  none of them natively, so no value conversion is implied.
