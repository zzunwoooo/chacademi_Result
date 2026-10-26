# Chacademi Result

Source consolidation in progress. This repository is NOT yet a complete or verified operating release. Do not deploy it to production.

## Intended layout
- `game/`: server plugins and client mods
- `launcher/`: launcher application and distribution manifest
- `deployment/`: sanitized configuration, release provenance and validation

## Required reconciliation
The operating release contains emergency fixes that are not all present in the original development branch. These must be recovered and compared before this repository is marked complete. Existing source repositories and production servers are not modified by this consolidation.

Credentials, SSH keys, database passwords, tokens and player data must not be committed.
