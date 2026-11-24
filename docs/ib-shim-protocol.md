# IB shim protocol `perkbridge.ibshim/v1`

Published copy of the protocol the `ib-tws-shim` program speaks. A client
is any program that connects to the shim's unix socket and uses the
messages below.

## Why a shim

Interactive Brokers publishes no wire specification of the TWS API. Its
message ids and field order exist only in IB's own client sources, which
IB distributes under the GNU General Public License (the TWS API 10.52.01
installer shows GPL version 3). A client therefore never speaks the TWS
API wire protocol itself. The **IB shim**, a small Java program, links
IB's official Java client and exposes the functionality a client needs
over a local unix socket in the protocol this document defines.

Boundary rules:

- The shim is published under GPL-3.0-only in its own repository. It
  contains no client code and no IB code.
- The shim **redistributes nothing of IB's**. IB's `TwsApi.jar` comes
  from IB's own installer on the host where the shim runs, and is passed
  to the shim at start with `--api-jar`.
- The shim and its client are separate processes that exchange only the
  messages below. The protocol is **venue-neutral in form**: another
  program can implement the same socket protocol, so the shim is a
  replaceable module.
- The shim holds **no credentials and no state**. Sign-in is the
  gateway's business; the shim connects to the gateway's API port only.
- The shim holds no business logic (risk, protections, reconciliation):
  it translates calls and callbacks one to one.

## Transport

- Unix stream socket. Default path `/run/ibshim/ibshim.sock` on a tmpfs
  volume of the companion container; owner `ibgw` (the shim's uid),
  group = the client's group, socket mode `0660`, directory
  `0750`.
- **One client at a time.** A second connection receives exactly
  `{"ok":false,"error":{"code":"busy"}}` (no `id`, since no request was
  read) and is closed.
- Framing: **newline-delimited JSON** (one UTF-8 JSON object per line,
  `\n` terminated, no line longer than 1 MiB). Unknown fields are
  ignored by both sides, **except** inside the `contract` and `order`
  objects of `place_order` and in the args of `cancel_order`, where an
  unknown key is refused with `bad_request` (a misspelled order field
  must never silently fall back to an IB default). Unknown message kinds
  are an error.
- Numbers: quantities, prices and money are **strings** holding decimal
  literals (IB uses `Decimal` quantities; no float rounding on the
  wire). Ids (order ids, request ids, client ids, perm ids, conIds) are
  JSON integers. Times are RFC 3339 UTC strings; where IB gives a string
  the shim cannot parse, the raw string is passed in a sibling field
  ending in `_raw` and the normalised field is omitted. Times the client
  sends (`good_till_date`, `executions.filter.time`,
  `historical_data.end_time`) are RFC 3339 and the shim converts them
  to IB's `yyyyMMdd HH:mm:ss UTC` form.
- Liveness: the shim sends `heartbeat` every 5 s while a client is
  connected; the client may send `ping`. A client that has not read for
  long enough that the shim's write buffer exceeds 64 MiB is
  disconnected with a logged reason `client_too_slow`. Events other than
  ticks are never silently dropped: either delivered in order or the
  connection ends and the client reconciles.
- **Ticks are conflatable.** `tick_price`, `tick_size`, `tick_string`
  and `tick_generic` may be replaced by a newer event with the same
  `req_id` and `tick_type` before they are sent, by the shim, and may be
  dropped by the client under load. A tick burst therefore never ends
  the connection and never makes an order outcome ambiguous.
- **Client loss.** When the client's socket closes, the shim disconnects
  from the gateway, discards every unsent event and returns to the
  listening state. The next client connects anew (`connect`) and runs
  client-side reconciliation (`req_ids`, `open_orders`,
  `completed_orders`, `executions`, `positions`). The shim never holds a
  gateway session nobody is listening to.

## Message kinds

Three shapes, distinguished by their keys:

```json
{"id": "<client-chosen string>", "op": "<name>", "args": {...}}          // request
{"id": "<same>", "ok": true,  "result": {...}}                            // response
{"id": "<same>", "ok": false, "error": {"code": "<code>", "message": "<text>", "venue_code": 0}}
{"event": "<name>", "seq": 123, "at": "<rfc3339>", "data": {...}}         // event
```

- Requests are answered exactly once, in any order. `id` is unique per
  connection.
- `seq` is a per-process counter starting at 1 at shim start and never
  reused while the process lives. A client that reconnects and sees a
  `seq` lower than the last one it saw knows the shim restarted and must
  reconcile.
- Error codes (string, stable): `bad_request`, `unsupported_op`,
  `not_connected`, `already_connected`, `busy`, `timeout`,
  `venue_error` (IB error relayed; `venue_code` carries IB's code),
  `internal`. IB's asynchronous errors arrive as the `error` **event**,
  not as a response, exactly as the TWS API reports them.

## Handshake

The first request on a connection must be `hello`.

```json
{"id":"1","op":"hello","args":{"protocol":"perkbridge.ibshim/v1","client":"example-client/1.0"}}
{"id":"1","ok":true,"result":{"protocol":"perkbridge.ibshim/v1","shim":"ib-tws-shim/0.2.0",
  "api_client":"<version string reported by the IB client library>",
  "ops":["connect","disconnect",...]}}
```

A `protocol` whose major part (`/v1`) differs is refused with
`unsupported_op` and the connection closes. Minor evolution is additive:
new ops, new optional args, new fields, new events. The `ops` list lets
the client refuse to start when an op it needs is missing.

## Requests

| op | args | result | notes |
|----|------|--------|-------|
| `hello` | `protocol`, `client` | see above | first message |
| `ping` | | `{}` | |
| `connect` | `host`, `port`, `client_id`, `connect_timeout_ms` | `server_version`, `connection_time`, `next_valid_id`, `accounts[]` | one gateway connection per shim; `already_connected` otherwise. Emits `connected`. |
| `disconnect` | | `{}` | emits `disconnected{reason:"requested"}` |
| `req_ids` | | `{}` | answer arrives as `next_valid_id` event |
| `server_time` | | `{}` | answer arrives as `current_time` event |
| `contract_details` | `req_id`, `contract` | `{}` | events `contract_details` …, `contract_details_end` |
| `place_order` | `order_id`, `contract`, `order` | `{}` | acceptance arrives as `open_order` and `order_status`; rejects as `error{order_id}`. With `order.what_if=true` nothing is placed: one `open_order` arrives whose `order_state` carries the margin preview |
| `cancel_order` | `order_id`, `manual_time` (optional) | `{}` | |
| `cancel_all` | | `{}` | global cancel; a client should use it only under an operator halt |
| `open_orders` | `all` (bool) | `{}` | one-shot: events `open_order` …, `open_order_end`; `all=true` = every client id |
| `completed_orders` | `api_only` (bool) | `{}` | events `completed_order` …, `completed_orders_end`; `api_only=false` includes orders placed outside the API |
| `executions` | `req_id`, `filter{client_id, account, time, symbol, sec_type, exchange, side}` | `{}` | events `exec_details` …, `exec_details_end`; IB serves today's fills only by default |
| `positions` | | `{}` | **subscription**: events `position` with `snapshot=true` …, `position_end`, then live `position` events with `snapshot=false` until `cancel_positions` |
| `cancel_positions` | | `{}` | |
| `account_summary` | `req_id`, `group`, `tags` | `{}` | **subscription**: events `account_summary` with `snapshot=true` …, `account_summary_end`, then live updates with `snapshot=false` until `cancel_account_summary` |
| `cancel_account_summary` | `req_id` | `{}` | |
| `account_updates` | `subscribe` (bool), `account` | `{}` | events `account_value`, `portfolio`, `account_time`, `account_download_end` |
| `market_data` | `req_id`, `contract`, `generic_ticks`, `snapshot` (bool) | `{}` | events `tick_price`, `tick_size`, `tick_string`, `tick_generic`, `tick_snapshot_end`; the shim always sends `regulatorySnapshot=false` (a regulatory snapshot is a paid per-request call) and no request key changes it |
| `cancel_market_data` | `req_id` | `{}` | |
| `market_data_type` | `type` (1 live, 2 frozen, 3 delayed, 4 delayed-frozen) | `{}` | |
| `historical_data` | `req_id`, `contract`, `end_time` (RFC 3339 or empty = now), `duration` (IB duration string, e.g. `1 D`), `bar_size` (IB bar size string, e.g. `1 min`), `what_to_show`, `use_rth` (bool), `keep_up_to_date` (bool) | `{}` | events `historical_bar` …, `historical_data_end`; with `keep_up_to_date` further `historical_bar` events with `update=true` until cancelled |
| `cancel_historical_data` | `req_id` | `{}` | |
| `market_rule` | `market_rule_id` | `{}` | event `market_rule`; ids come from `contract_details.market_rule_ids` |
| `pnl_single` | `req_id`, `account`, `model_code`, `con_id` | `{}` | events `pnl_single` until `cancel_pnl_single` |
| `cancel_pnl_single` | `req_id` | `{}` | |

Reserved for later minors (not in v1.0): `pnl` (account level),
`news_bulletins`.

### Objects

`contract`: `con_id`, `symbol`, `sec_type`, `exchange`,
`primary_exchange`, `currency`, `local_symbol`, `trading_class`,
`last_trade_date`, `strike` (string), `right`, `multiplier` (string). All
optional except what IB needs for the call; the shim passes them through.

`order`: `action` (`BUY`/`SELL`), `order_type` (`MKT`, `LMT`, `STP`,
`STP LMT`), `total_quantity` (string), `lmt_price` (string),
`aux_price` (string), `tif` (`DAY`, `GTC`, `GTD`, `IOC`, `FOK`; `GTD`
requires `good_till_date`), `order_ref`,
`account`, `oca_group`, `oca_type` (int), `parent_id`, `transmit`
(bool), `outside_rth` (bool), `good_till_date` (RFC 3339), `what_if` (bool). Fields the client does
not set are omitted and the shim leaves the IB defaults.

`order` in `open_order` and `completed_order` also carries what the venue
reports about an order (v0.2.0, additive; never accepted in an order to
place): `perm_id` (int; IB's permanent order id, `0` until the gateway has
assigned one; unlike `order_id` it is unique per account and the same for an
order placed in TWS, whose `order_id` is `0`), `trail_stop_price` (string)
and `trailing_percent` (string), the last two only when the order has them.

`order_state` (in `open_order`): `status`, `commission` (string),
`commission_currency`, `warning_text`, `init_margin_change`,
`maint_margin_change`, `equity_with_loan_change` (strings).

`execution` (in `exec_details`): `exec_id`, `time`, `time_raw`,
`account`, `exchange`, `side`, `shares` (string), `price` (string),
`perm_id`, `client_id`, `order_id`, `liquidation`, `cum_qty` (string),
`avg_price` (string), `order_ref`, `last_liquidity`.

## Events

| event | data | source |
|-------|------|--------|
| `connected` | `server_version`, `connection_time`, `client_id` | after `connect` |
| `disconnected` | `reason` (`requested`, `gateway_closed`, `error`), `message` | gateway socket closed; `client_gone` is logged only, nobody is connected to receive it |
| `connectivity` | `state` (`lost` = IB 1100, `restored_orders_lost` = 1101, `restored_orders_kept` = 1102) | emitted in addition to the `error` event carrying the same code |
| `heartbeat` | `connected` (bool) | every 5 s |
| `next_valid_id` | `order_id` | `nextValidId` |
| `managed_accounts` | `accounts[]` | `managedAccounts` |
| `current_time` | `time`, `epoch` | `currentTime` |
| `error` | `req_id` (or `order_id`, -1 when none), `code`, `message`, `severity`, `advanced_order_reject` (raw JSON string, optional) | `error` callback. `severity` is derived from IB's code ranges only: `connectivity` for 1100 to 1102, `info` for 2100 to 2169 (farm status and warnings, e.g. 2104, 2106, 2158), `error` for everything else. Mapping of codes is the client's business |
| `completed_order` | `contract`, `order`, `order_state` | `completedOrder` |
| `completed_orders_end` | | `completedOrdersEnd` |
| `open_order` | `order_id`, `contract`, `order`, `order_state` | `openOrder` |
| `open_order_end` | | `openOrderEnd` |
| `order_status` | `order_id`, `status`, `filled` (string), `remaining` (string), `avg_fill_price` (string), `perm_id`, `parent_id`, `last_fill_price` (string), `client_id`, `why_held`, `mkt_cap_price` (string) | `orderStatus` |
| `exec_details` | `req_id`, `contract`, `execution` | `execDetails` |
| `exec_details_end` | `req_id` | `execDetailsEnd` |
| `commission_report` | `exec_id`, `commission` (string), `currency`, `realized_pnl` (string), `yield` (string), `yield_redemption_date` | `commissionReport` |
| `position` | `account`, `contract`, `position` (string), `avg_cost` (string), `snapshot` (bool) | `position` |
| `position_end` | `as_of_seq` | `positionEnd`; `as_of_seq` is the `seq` of the last snapshot event, so the client can order live updates against the snapshot |
| `account_summary` | `req_id`, `account`, `tag`, `value`, `currency`, `snapshot` (bool) | `accountSummary` |
| `account_summary_end` | `req_id`, `as_of_seq` | `accountSummaryEnd`; `as_of_seq` as for `position_end` |
| `account_value` | `key`, `value`, `currency`, `account` | `updateAccountValue` |
| `portfolio` | `contract`, `position` (string), `market_price`, `market_value`, `average_cost`, `unrealized_pnl`, `realized_pnl` (strings), `account` | `updatePortfolio` |
| `account_time` | `time_raw` | `updateAccountTime` |
| `account_download_end` | `account` | `accountDownloadEnd` |
| `contract_details` | `req_id`, `contract`, `market_name`, `min_tick` (string), `order_types`, `valid_exchanges`, `long_name`, `time_zone_id`, `trading_hours`, `liquid_hours`, `stock_type`, `min_size`, `size_increment` (strings), `market_rule_ids` (string, comma separated as IB gives it) | `contractDetails` |
| `market_rule` | `market_rule_id`, `increments[]` of `{low_edge, increment}` (strings) | `marketRule` |
| `historical_bar` | `req_id`, `time`, `time_raw`, `open`, `high`, `low`, `close`, `volume`, `wap` (strings), `count`, `update` (bool) | `historicalData`, `historicalDataUpdate` |
| `historical_data_end` | `req_id`, `start_raw`, `end_raw` | `historicalDataEnd` |
| `pnl_single` | `req_id`, `position`, `daily_pnl`, `unrealized_pnl`, `realized_pnl`, `value` (strings) | `pnlSingle` |
| `contract_details_end` | `req_id` | `contractDetailsEnd` |
| `tick_price` | `req_id`, `tick_type`, `price` (string), `attrib{can_auto_execute, past_limit, pre_open}` | `tickPrice` |
| `tick_size` | `req_id`, `tick_type`, `size` (string) | `tickSize` |
| `tick_string` | `req_id`, `tick_type`, `value` | `tickString` |
| `tick_generic` | `req_id`, `tick_type`, `value` (string) | `tickGeneric` |
| `tick_snapshot_end` | `req_id` | `tickSnapshotEnd` |

Callbacks the shim receives and this version does not forward are
counted in the shim's log line `dropped_callbacks` so a later minor can
add them deliberately. Status strings and tick types are IB's own
values; mapping them to a client's own states is the client's business, not the shim's.

## Process contract

- Binary: `ib-tws-shim.jar`, started by a supervisor: `java -jar ib-tws-shim.jar --socket /run/ibshim/ibshim.sock
  --api-jar <path to downloaded TwsApi.jar>[:<more jars>] --log-level info`.
  `--api-jar` takes one or more jars separated by `:`. IB's `TwsApi.jar`
  of some versions (e.g. 1050.02) needs `protobuf-java` at run time, so
  pass both, e.g. `--api-jar TwsApi.jar:protobuf-java.jar`. The shim does
  not ship protobuf; the jar comes from the host.
- Exit codes: `0` on `SIGTERM`, `2` bad arguments, `3` API jar missing
  or unloadable, `4` socket path unusable. The shim also exits `1` if its accept loop ends unexpectedly.
  A supervisor should treat any exit as a failure.
- Logs: one JSON object per line on stderr (`ts`, `level`, `msg`,
  fields). Never logs message payloads at `info`; `debug` logs them
  without `account` values.
- Resources: `-Xmx256m` default; the hosting guide gains the shim's
  footprint once measured (not verified).
- Readiness: the socket exists and `hello` succeeds. No HTTP, no
  metrics port; a client observes shim health through heartbeats.

## Testing

The repository's tests run the shim against a hand-written test double of
IB's client classes. The test double is test-only and is not packaged.
Running the shim against IB's real `TwsApi.jar` and a real gateway (IB
paper) is NOT VERIFIED yet.
