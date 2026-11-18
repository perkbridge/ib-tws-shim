# IB licence notes

Status on 2026-11-18: IB's TWS API licence text was pasted by the PO and it is
the standard GNU GPL version 3 (header "Version 3, 29 June 2007"). This agrees
with IB's changelog statement. Not yet confirmed: which exact package version the
text came from (the release thread's `ib-pin refresh` will quote the LICENSE file
of the pinned package), and whether the pasted text carries any additional terms
or exception after the standard GPLv3 body (the relay copy was truncated, so only
the opening was seen here). The conflicting third-party PyPI "Non-Commercial"
label noted above is still unexplained. The repository licence (`LICENSE`,
GPL-3.0-or-later) stays as is.

## What was verified

IB changelog, https://www.interactivebrokers.com/docs/tws-api/changelog, entry dated
2026-08-03 (fetched 2026-11-18), quoted verbatim:

> TWS API 10.49+ is released under the GNU General Public License (GPL).

The line names no GPL version and no exception.

## Conflicting signal, not resolved

A third-party PyPI repackager of the Python client (not IB) still says the `ibapi`
source is "licensed under the TWS API Non-Commercial License" and that commercial
use needs a written agreement with IB. This is probably stale text from packages
before 10.49, or IB dual-licenses. Only the licence file inside the official 10.49+
installer settles it.

## Download facts

- IB no longer publishes a `.zip`; the old `twsapi_macunix.*.zip` links return 404.
- Install page: https://download2.interactivebrokers.com/installers/tws-api/index.html
- `stable/tws-api-stable-linux-x64.sh` (install4j installer), fetched 2026-11-18:
  61,566,217 bytes, SHA-256
  `02247a284feda91da4904e5e3d851f20b272124aa91c20ca6097112521ab61df`.
  The version string of that build was not read. The URL is a moving "stable" link, so
  `scripts/fetch-twsapi.sh` pins the hash and fails when IB replaces the file.
- The licence file is inside the installer's packed data. It was not extracted: the
  installer was not run in the review sandbox.

## Boundary this repository keeps

- No file from IB is stored here or inside `ib-tws-shim.jar`.
- IB's classes are reached by name at run time from the jar given with the `--api-jar`
  option. The source here refers to IB class and method names only.
- IB's client source was not read. Class and method names used are those of the
  public API as documented.

## To do when the licence text is available

1. Paste it verbatim below.
2. Record: GPL version, any exception or dual-licence notice, whether commercial use
   needs IB's agreement.
3. If it is non-commercial only or otherwise incompatible with "we link it at the
   customer's site, redistribute nothing of IB's, publish our adapter under GPL",
   stop: the adapter holds no IB code, so discarding it unwinds nothing else.

## Licence file text (installer)

Source: pasted by the PO (message in the release thread, 2026-11-18 18:21Z), not
read by Claude from the installer. Package version: not yet confirmed.

Seen: the standard GNU General Public License, Version 3, 29 June 2007, with its
usual Preamble and Terms and Conditions. The copy available to this thread was
cut off partway through section 2, so the end of the text (and any added terms)
is NOT VERIFIED here. To close this: compare the LICENSE file from the pinned
package (`ib-pin refresh`) with the GPLv3 text in `LICENSE`.
