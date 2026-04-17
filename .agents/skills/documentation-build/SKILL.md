---
name: documentation-build
description: >
  How to build, preview, and incrementally rebuild the Quarkus documentation
  site locally using Maven, sync scripts, and a containerized Jekyll server.
---

# Documentation Build

## Full Build

Build the entire documentation (from the repository root):

```bash
mvn clean install -DskipTests -Denforcer.skip -Dcheckstyle.skip -DskipITs=true -Dmaven.javadoc.skip=true -T 1C
```

## Preview

From the `docs/` directory, sync the generated site:

```bash
cd docs/
./sync-web-site.sh
```

Then start a local Jekyll server with Podman:

```bash
podman run -p 4000:4000 -v "$(pwd)/target/web-site:/site:z" bretfisher/jekyll-serve bundle exec jekyll serve --incremental
```

The site is available at `http://localhost:4000`.

## Incremental Rebuild

While the preview container is running, rebuild changed docs without a full
clean build:

```bash
mvn package -Dno-build-cache
```

Then re-run `./sync-web-site.sh` in `docs/` to pick up the changes. The Jekyll
server auto-reloads updated files.
