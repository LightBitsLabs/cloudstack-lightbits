# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Initial repository scaffolding: license and project hygiene files, Maven build skeleton.
- Stub implementations of the CloudStack storage SPI (`DataStoreProvider`, `PrimaryDataStoreLifeCycle`, `PrimaryDataStoreDriver`) against CloudStack 4.22.0.0 — compile skeleton only, no behavior.
- CI workflow that builds the plugin against a source-built CloudStack 4.22.0.0 (CloudStack does not publish Maven artifacts to Maven Central).

### Known limitations
- No functional code yet: every SPI method throws `UnsupportedOperationException`.
- No KVM-agent `StorageAdaptor` (NVMe/TCP host-side attach) yet.
- No Spring module wiring (`module.properties` / context XML) yet — the plugin is not loadable by a CloudStack management server.
