# uplink

A broken-link checker for websites, built with [Quarkus](https://quarkus.io)
4.0.0.Beta1 and [TamboUI](https://tamboui.dev), compiled to a native executable.

```bash
uplink https://aletyx.ai
```

uplink crawls every page on the target site, recursively following links on the
same host (`www.` is ignored, so `example.com` and `www.example.com` count as one
site). Links to other sites, including other subdomains, are checked once and
never followed, so it never crawls beyond the target. At the end it reports the
number of good links and lists each bad one with the page it was found on.

Results fall into three groups:

| Result | Meaning |
|--------|---------|
| Good | 2xx/3xx response (redirects are followed) |
| Broken | 4xx/5xx, DNS failure, refused connection, timeout or TLS error |
| Unverified | The server refused automated access (401, 403, 429, LinkedIn's 999); the link probably works in a browser |

External links are checked with `HEAD`, falling back to `GET` when the server
rejects `HEAD`. Each request is retried once after a network error or a 429/502/503/504.

Every link is checked on its own virtual thread. Concurrency is limited per host
(`--concurrency`, default 8), so the target site is not hammered, while different
hosts are checked in parallel up to `--max-in-flight` (default 64) requests overall.

### Desktop vs CI/CD

At startup uplink picks its front end:

- **CI/CD → console logs.** It detects CI/CD from provider variables first:
  `GITHUB_ACTIONS`, `CODEBUILD_BUILD_ID` (AWS CodeBuild sets no `CI`
  variable), `GITLAB_CI`, `JENKINS_URL`, `TF_BUILD`, `BUILDKITE`, `CIRCLECI`
  and more. Then it checks the generic `CI` variable. Output that isn't a
  terminal (a pipe or a redirect) and `TERM=dumb` also count as non-interactive.
  The site is crawled once. Each bad link is logged when it's found, a progress
  summary is printed every 30 seconds, and a totals report comes at the end.
  The exit code is `0` when no link is broken and `1` when any is.
- **Desktop → monitoring dashboard.** The site is crawled again and again,
  with `--interval` seconds (default 30) between passes, until you press
  **Ctrl+C**. The dashboard shows the current and previous pass, the links that
  are currently broken or unverified, the slow ones (`--slow`, default 1000 ms)
  and an event log. The log records new broken links, recoveries, slow links,
  links removed from the site and pass summaries. The report of the last
  completed pass is printed when it stops. The dashboard has three tabs,
  switched with `1`–`3` or `←`/`→`:
  - **Summary**: the view described above.
  - **Broken**: every broken or unverified link with its status code (`ERR`
    when there was no response), the reason and the page it was found on,
    with a count per code in the title.
  - **Latency**: the site's pages, slowest first, with p50/p95/max, next to
    a histogram of page latencies.

  Scroll the Broken and Latency lists with `↑`/`↓`, `PgUp`/`PgDn`, `Home` and `End`.

Override the detection with `--mode=tui` or `--mode=console`.

### Options

```
uplink [-hV] [-c=<per-host>] [--max-in-flight=<n>] [--max-pages=<n>]
       [--mode=auto|tui|console] [--interval=<seconds>] [--slow=<ms>]
       [--summary-interval=<seconds>] [-t=<seconds>] URL
```

Exit codes: `0` no broken links, `1` broken links found (fails the CI job),
`2` invalid arguments. Unverified links do not fail the run.

### Build and install

Requires GraalVM 25 (`native-image`); Maven comes from the wrapper.

```bash
./mvnw test                          # unit + end-to-end tests
./mvnw package -Dnative              # -> target/uplink
scripts/install.sh                   # build and copy to ~/.local/bin/uplink
PREFIX=/opt/homebrew/bin scripts/install.sh
```

The same steps are available as `make` targets, which call [make.sh](make.sh):
`make` (native build), `make jvm`, `make test`, `make install`, `make clean`.
Run `./make.sh help` to list them.

Builds are versioned `X.Y.Z`: `X` and `Y` from [version.X.txt](version.X.txt)
and [version.Y.txt](version.Y.txt), `Z` the build time as `YYYYMMDDHHMM` (UTC),
see [scripts/version.sh](scripts/version.sh). `uplink --version` prints it
(`uplink dev` for a plain `./mvnw` build).

### Releases

Native executables for Linux (x64, arm64) and macOS (Apple Silicon) are
published as [GitHub releases](https://github.com/prodbytes/uplink/releases),
each with a `SHA256SUMS` file. A release is started by pushing a tag:

| Command | Tag | Workflow | Published as |
|---------|-----|----------|--------------|
| `bash scripts/release-rc.sh` | `X.Y.Z-RC` | [Release RC](.github/workflows/release-rc.yml) | prerelease |
| `bash scripts/release-ga.sh` | `X.Y.Z-GA` | [Release GA](.github/workflows/release-ga.yml) | latest release |

Both workflows call [Build](.github/workflows/build.yml), which runs the
tests, builds each platform natively, checks that the binary reports the tag's
version and publishes the release. A GA release must be a commit on `main`;
an RC can be any pushed commit. `DRY_RUN=1` prints the tag without creating
it. Bump `version.X.txt` or `version.Y.txt` for a new major or minor version.

Native image notes: the TamboUI Panama backend uses the Foreign Function &
Memory API, so the build enables `-H:+ForeignAPISupport`
([application.properties](src/main/resources/application.properties)). The
backend is created directly rather than through `ServiceLoader`, because
Quarkus native images do not register service providers automatically.

---

## Development environment

Your new project canvas: a blank, batteries-included dev environment powered by
[Devbox](https://www.jetify.com/devbox) inside a [Dev Container](https://containers.dev/).

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/prodbytes/blank-devbox)
[![Open in Dev Containers](https://img.shields.io/static/v1?label=Dev%20Containers&message=Open&color=007ACC&logo=visualstudiocode)](https://vscode.dev/redirect?url=vscode://ms-vscode-remote.remote-containers/cloneInVolume?url=https://github.com/prodbytes/blank-devbox)

### What's inside

Toolchain pinned by [devbox.json](devbox.json) and locked in [devbox.lock](devbox.lock):

| Tool | Version |
|------|---------|
| GraalVM CE | 25.0.2 |
| Node.js | 26.x |
| Python | 3.14.x |
| PostgreSQL | 17.x |

The container also ships the
[docker-in-docker feature](https://github.com/devcontainers/features/tree/main/src/docker-in-docker),
so `docker ps` works out of the box.

### Getting started

Click a badge above, or locally:

```bash
git clone git@github.com:prodbytes/blank-devbox.git
code blank-devbox   # then "Reopen in Container" when prompted
```

Once inside the container:

```bash
devbox shell        # enter the environment
devbox run node --version
devbox add go@1.24  # add more tools (updates devbox.json + devbox.lock)
```

### Services

```bash
devbox services up
```

starts PostgreSQL as a Docker container (`devbox-db`, defined in
[compose.yaml](compose.yaml)) plus a `health-check` monitor wired up in
[process-compose.yaml](process-compose.yaml). A readiness probe holds the
monitor back until the database accepts connections; after that it logs one
status line per check (every 15 s, configurable via `HEALTH_CHECK_INTERVAL`):

```
2026-07-09 20:02:10 🐘 database ✅
```

Stop everything with `devbox services stop`. The monitor also runs standalone:
`bash scripts/health-check.sh`.

### How the container is built

The [Containerfile](.devcontainer/Containerfile) keeps the Microsoft
`ubuntu-24.04` devcontainer base image and layers Devbox on top:

1. Devbox is installed as root, then everything else runs as the `vscode` user
   so the Nix store ownership matches the container's `remoteUser`.
2. Nix is installed in single-user mode (`--no-daemon`) — containers have no
   systemd, so the multi-user Nix daemon can't run.
3. At build time the locked store paths are fetched straight from
   `cache.nixos.org` to warm `/nix/store` (no GitHub API calls, so builds
   don't hit unauthenticated rate limits).
4. On container start, `postCreateCommand` runs `devbox install`, which finds
   the heavy downloads already cached. The first install still evaluates
   nixpkgs, which takes a few minutes; after that the environment is instant.
