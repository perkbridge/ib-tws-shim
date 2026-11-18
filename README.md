# ib-tws-shim

A protocol adapter. It puts the `perkbridge.ibshim/v1` protocol on a unix socket in
front of Interactive Brokers' official Java client (TWS API), so that a program in any
language can use IB's API without linking IB's code.

Licence: GPL-3.0-or-later (see `LICENSE`; the open licence question about IB's side is
in `LICENSE-IB-NOTES.md`).

## What it is and is not

- It translates ops into calls on IB's client and IB's callbacks into events, one to
  one. It contains no trading logic, holds no credentials and keeps no state.
- It contains **no IB code**. IB's `TwsApi.jar` is not in this repository and not in
  `ib-tws-shim.jar`. You pass it at start with the `--api-jar` option.
- It connects to the API port of an IB Gateway or TWS that someone else has signed in.
  Sign-in is not its business.
- One client at a time. A second connection gets `{"ok":false,"error":{"code":"busy"}}`.

## The protocol

The protocol is documented in `docs/ib-shim-protocol.md` of the PerkBridge `nile`
repository (private at the time of writing). A public copy URL belongs here once it
exists. In short: newline-delimited JSON over the unix socket, requests with an `id`,
responses, and events with a per-process `seq`; money and quantities are decimal strings.

## What it needs at run time

- Java 17 or newer.
- IB's `TwsApi.jar` from the official TWS API installer (API 10.49 or newer; IB's
  changelog says 10.49+ is released under the GPL). In the installer's tree it is
  expected under a `JavaClient` directory (NOT VERIFIED: path not checked against a
  real install). `scripts/fetch-twsapi.sh` downloads the installer by pinned SHA-256
  and runs it unattended in a throwaway directory.
- A gateway to connect to (`connect` op: host, port, client id).

```
java -Xmx256m -jar ib-tws-shim.jar --socket /run/ibshim/ibshim.sock \
     --api-jar /path/to/TwsApi.jar --log-level info
```

| Option | Meaning |
|--------|---------|
| `--socket` | unix socket path; created with mode 0660; start with `umask 0007` and a setgid directory for the group |
| `--api-jar` | path of IB's `TwsApi.jar`; several jars may be given separated by `:` (newer IB libraries need extra jars, for example protobuf) |
| `--log-level` | `debug`, `info`, `warn` or `error` (default `info`) |

The API jars are linked at start: a missing class of IB's library exits with code 3 instead of failing at the first `connect`.

Exit codes: `0` stopped by SIGTERM, `1` the accept loop ended unexpectedly, `2` bad arguments, `3` API jar missing or unusable,
`4` socket path unusable. Logs are one JSON object per line on stderr. At `info` no
message payloads are logged.

## Build and test

```
mvn package          # builds target/ib-tws-shim.jar and runs the tests
```

The jar has no runtime dependencies. The tests use a small test double of IB's client
classes (`src/test/java/com/ib/client`, written for this repository, not IB code); they
cover framing, the busy refusal, tick conflation, a slow client, client loss, argument
validation, event shapes and the process contract (exit codes, class loading from
`--api-jar`, SIGTERM).

What the tests cannot show: that the real IB library accepts the calls the adapter makes
by reflection (method and field names), and every behaviour of a real gateway. Both are
NOT VERIFIED until the shim runs against `TwsApi.jar` and an IB paper account.

## How IB's classes are reached

The adapter calls IB's classes by name through reflection, with aliases for names that
changed between library versions (for example `cancelOrder` taking a string or an
`OrderCancel`). Nothing needs IB's jar to compile. If a call does not match the loaded
library, the op fails with `unsupported_op` and a log line `api_mismatch`; the shim does
not guess.
