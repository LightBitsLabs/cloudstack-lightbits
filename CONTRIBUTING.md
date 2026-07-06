# Contributing

Thank you for your interest in contributing to the Lightbits CloudStack plugin.

## How to contribute

1. Open an issue describing the bug or feature before starting significant work.
2. Fork the repository and create a topic branch.
3. Keep changes focused; one logical change per pull request.
4. Include tests where behavior changes, and describe manual testing performed in the PR description (environment, CloudStack version, Lightbits version, steps).
5. All contributions are accepted under the [Apache License 2.0](LICENSE).

## Code style

- Java 17, standard CloudStack plugin conventions — when in doubt, follow the patterns used by in-tree storage plugins in `apache/cloudstack` under `plugins/storage/volume/`.
- No proprietary dependencies: this plugin is intended for upstream contribution to Apache CloudStack, so everything must remain ASF-license compatible.

## Reporting security issues

Please do not open public issues for security vulnerabilities — see [SECURITY.md](SECURITY.md).
