# Contributing

Thank you for your interest in contributing to the Lightbits CloudStack plugin.

## How to contribute

1. Open an issue describing the bug or feature before starting significant work.
2. Fork the repository and create a topic branch.
3. Keep changes focused; one logical change per pull request.
4. Include tests where behavior changes. Unit tests live under `src/test/java` and run with `mvn verify` (no cluster or management server needed — the Lightbits API is stubbed with WireMock); see the README "Testing" section for the layout. New API calls should ship with a WireMock-backed test covering both the happy path and the failure it must handle. Also describe any manual testing performed in the PR description (environment, CloudStack version, Lightbits version, steps).
5. All contributions are accepted under the [Apache License 2.0](LICENSE).

## Code style

- Java 17, standard CloudStack plugin conventions — when in doubt, follow the patterns used by in-tree storage plugins in `apache/cloudstack` under `plugins/storage/volume/`.
- No proprietary dependencies: everything must remain ASF-license compatible, so that future upstream contribution to Apache CloudStack stays possible.

## Reporting security issues

Please do not open public issues for security vulnerabilities — see [SECURITY.md](SECURITY.md).
