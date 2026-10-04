# Agent instructions

## Before committing and pushing

Review all new and modified code for **correctness** and **security** before
every commit and push:

- **Correctness**: verify the change does what it claims — run the relevant
  code path (build, script, or service), not just a syntax check. For this
  repo that typically means the devcontainer image still builds and
  `devbox services up` still starts the database and health check cleanly.
- **Security**: check for hardcoded secrets or tokens, unsafe shell patterns
  (unquoted variables, `curl | bash` of unpinned sources, world-writable
  files), injection risks, and unnecessarily broad permissions or exposed
  ports. Never commit credentials, even placeholders that look real.

Do not commit or push code that has not passed both checks.

## Project notes

- Toolchain is managed by [devbox.json](devbox.json); enter it with
  `devbox shell` and keep [devbox.lock](devbox.lock) committed.
- `devbox services up` starts PostgreSQL as the `devbox-db` Docker container
  ([compose.yaml](compose.yaml)) plus the health monitor
  ([scripts/health-check.sh](scripts/health-check.sh)); stop with
  `devbox services stop`. In non-interactive contexts pass
  `--pcflags "--tui=false"`.
- The devcontainer build is documented in the [README](README.md).
