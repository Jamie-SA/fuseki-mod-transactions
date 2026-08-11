# Running fuseki-mod-transactions in a container (Podman/Docker)

This explains how to layer `fuseki-mod-transactions` onto Apache Jena's own
Fuseki container image. It assumes you've read the main
[README.md](README.md) (the module itself, its TDB2-only restriction, the
curl examples).

Commands below use **`podman`**. `podman build`/`podman run` accept the same
flags as their `docker` equivalents, so substituting `docker` for `podman`
throughout works unmodified if that's what you use instead -- with one
exception called out below (rootless UID mapping on the bind-mounted volume,
which is a podman-specific concern since Docker Desktop/Engine typically runs
its daemon as root).

Both `podman build` and `docker build` need an actual build file present in
the build context directory -- there's nothing to build from otherwise.
Podman looks for `Containerfile` first and falls back to `Dockerfile` if that
doesn't exist; Docker only looks for `Dockerfile`. This repo's root ships a
`Dockerfile` (not `Containerfile`) specifically so `podman build .` and
`docker build .` both find it with no extra `-f` flag needed, alongside the
`entrypoint.sh` it `COPY`s in and a `compose.yaml` -- all three are real files
here, not just the markdown snippets below (which quote their actual content
so you can see what they do without opening them separately).

## How Jena's own Fuseki image works

The build lives in the Jena source tree at `jena-fuseki2/jena-fuseki-docker/`
(named for Docker, but the `Dockerfile` there is a standard OCI build file --
`podman build` reads it directly, no translation needed). A few things about
it matter for adding a module:

- **Not published to a registry.** There's no `apache/jena-fuseki` (or
  similar) image on Docker Hub / quay.io — you build it yourself from that
  directory.
- **It's a single fat jar**, `jena-fuseki-server-${JENA_VERSION}.jar`
  (`jena-fuseki-main` + the UI, shaded together via `maven-shade-plugin`),
  copied to `/fuseki/jena-fuseki-server-${JENA_VERSION}.jar` inside the
  image. `FUSEKI_DIR=/fuseki` and `FUSEKI_JAR=jena-fuseki-server-${JENA_VERSION}.jar`
  are set as image env vars.
- **The entrypoint launches it with `-jar`:**
  ```sh
  exec "$JAVA_HOME/bin/java" $JAVA_OPTIONS -jar "${FUSEKI_DIR}/${FUSEKI_JAR}" "$@"
  ```
  `JAVA_OPTIONS` (default `-Xmx4096m -Xms4096m`) is the only documented
  override point.
- **`-jar` ignores `-cp`/`-classpath` entirely** (a plain JVM rule, not
  Jena-specific, and identical under podman or docker) — the classpath comes
  solely from the jar's own manifest. So there is no way to make an extra
  `META-INF/services`-discovered `FusekiAutoModule` jar visible just by
  mounting it into the container or setting `JAVA_OPTIONS`; the launch
  command itself has to change from `-jar <fatjar>` to
  `-cp <fatjar>:<your-jar> <main-class>`.
- The fat jar's manifest `Main-Class` is `org.apache.jena.fuseki.main.cmds.FusekiServerCmd`
  (from `jena-fuseki2/jena-fuseki-server/pom.xml`'s shade-plugin config) --
  that's what has to be named explicitly once you switch off `-jar`.
- The fat jar already bundles jena-tdb2 and a logging backend, so **our
  module's own jar needs nothing else on the classpath** beyond itself and
  the fat jar -- don't include this project's `slf4j-simple` runtime
  dependency in the container; it would just be a second, redundant logging
  backend fighting with the one already inside `jena-fuseki-server`.

## Build the official image

From a checkout of the Jena source (only needed once, or whenever you want a
newer Jena version):

```sh
cd jena-fuseki2/jena-fuseki-docker
podman build --build-arg JENA_VERSION=6.2.0 -t fuseki .
```

This produces a local image tagged `fuseki`. (`JENA_VERSION` is mandatory --
the build refuses to proceed without it.)

**This step is not obvious if you're coming from `docker.io/stain/jena-fuseki`**
(a common third-party pre-built Fuseki image) -- that image is unrelated to
this workflow and doesn't have this module's classpath changes baked in.
There is no official `apache/jena-fuseki` image on any registry (see above);
this local build step is mandatory, not optional, for this module's own
`Dockerfile` (below) to have a `fuseki:latest` base to `FROM`.

## Layer this module on top

This module's own directory already contains a `Dockerfile` and
`entrypoint.sh` that build the module jar and layer it onto `fuseki` with the
entrypoint swapped to use `-cp` instead of `-jar`:

**`Dockerfile`:**
```dockerfile
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM fuseki:latest
USER root
RUN mkdir -p /fuseki/modules \
    && chown fuseki:fuseki /fuseki/modules
COPY --from=build /build/target/fuseki-mod-transactions-*.jar /fuseki/modules/
COPY entrypoint.sh /fuseki/entrypoint.sh
RUN chmod +x /fuseki/entrypoint.sh && chown fuseki:fuseki /fuseki/entrypoint.sh
USER fuseki
```

`FROM fuseki:latest` resolves to the image you just built locally with
`podman build -t fuseki .` above -- podman tags locally-built images under
the `localhost/` registry namespace internally (you'd see it as
`localhost/fuseki:latest` in `podman images`), but the plain `fuseki:latest`
form here resolves correctly without needing the explicit prefix.

**Podman may refuse to resolve an unqualified base image at all, on some
machines.** The `maven:3.9-eclipse-temurin-21` build-stage base above is
written with the explicit `docker.io/library/` prefix specifically to avoid
this: podman (unlike Docker) only pulls a short, registry-less image name
(e.g. plain `eclipse-temurin:21-alpine`) if it has an unqualified-search
registry list configured (`unqualified-search-registries` in
`/etc/containers/registries.conf`); if that list is empty or missing, the
pull just fails rather than silently defaulting to Docker Hub the way Docker
itself does. If you change either `FROM` line in this project's `Dockerfile`,
keep the full `docker.io/library/<image>` (or your actual registry) form
rather than reverting to a short name, or the build may only work on
machines that happen to have that config set.

**`entrypoint.sh`** (replaces the base image's own, reusing its env vars so
this doesn't need to hardcode a Jena version):
```sh
#!/bin/sh
# Replaces the base Jena Fuseki image's own entrypoint.sh (which launches
# with `java -jar`, silently ignoring any classpath additions). This launches
# the same fat jar with an explicit classpath instead, so that a
# FusekiAutoModule jar dropped into /fuseki/modules/ is discovered via
# META-INF/services at startup. See DOCKER.md for the full explanation.
exec "$JAVA_HOME/bin/java" $JAVA_OPTIONS \
    -cp "${FUSEKI_DIR}/${FUSEKI_JAR}:${FUSEKI_DIR}/modules/*" \
    org.apache.jena.fuseki.main.cmds.FusekiServerCmd "$@"
```

Build it:

```sh
podman build -t fuseki-with-transactions .
```

## Run it

**The module only wires itself onto datasets that `supportsTransactionAbort()`**
(see the main README) -- confirmed for TDB2 and TIM (Jena's in-memory
transactional dataset). As of Jena 6.2.0, Fuseki's own `--mem` CLI flag
actually builds a TIM dataset (`DatasetGraphFactory.createTxnMem()`, checked
directly against `jena-fuseki-main`'s `DSGSetup` class, not assumed) -- so
`--mem` datasets *do* get the `transactions` endpoint too, contrary to what
an earlier version of this doc claimed. Non-transactional datasets (no
`transactions` endpoint) only come from more unusual setups, e.g. a custom
assembler explicitly using `DatasetGraphFactory.create()`. `--tdb2` remains
the flag to reach for when you want on-disk persistence rather than TIM's
purely in-memory storage:

```sh
mkdir -p databases/DB2
podman run -i --rm -p 3030:3030 \
    --userns=keep-id \
    --mount type=bind,src="$PWD/databases",dst=/fuseki/databases \
    --name fuseki-transactions -t fuseki-with-transactions \
    --tdb2 --update --loc databases/DB2 /ds
```

**`--userns=keep-id` matters here and is podman-specific.** The base image
creates a fixed `fuseki` user at uid/gid 1000 and runs as that user. Rootless
podman, by default, remaps container UIDs into a private subordinate-UID range
on the host rather than using them literally -- so without `--userns=keep-id`,
the container's uid-1000 process doesn't actually correspond to your host
user, and writes to the bind-mounted `databases` directory fail with a
permission error even though `ls -l` shows what looks like the right owner.
`--userns=keep-id` maps container uid 1000 back to your own host uid, which
does own `./databases` since you just `mkdir`'d it. (Rootful `docker`, by
contrast, doesn't hit this because its daemon runs as root and Linux doesn't
apply the same remapping.) If you'd rather not deal with UID mapping at all,
a named volume instead of a bind mount avoids the issue entirely, at the cost
of not being able to browse the TDB2 files directly from the host:
```sh
podman volume create fuseki-databases
podman run ... --mount type=volume,src=fuseki-databases,dst=/fuseki/databases ...
```

Or the included `compose.yaml` (mirrors the official `docker-compose.yaml`,
adding the persistent `databases` volume -- works unmodified with either
`podman-compose` or the newer built-in `podman compose`):

```yaml
services:
  fuseki:
    build:
      context: .
      dockerfile: Dockerfile
    image: fuseki-with-transactions
    command: [ "--tdb2", "--update", "--loc", "databases/DB2", "/ds" ]
    ports:
      - "3030:3030"
    volumes:
      - ./logs:/fuseki/logs
      - ./databases:/fuseki/databases
    # Rootless podman only: keeps the container's fixed uid-1000 "fuseki"
    # user mapped to your own host uid, so the bind-mounted ./databases
    # directory is actually writable. See DOCKER.md. Harmless/ignored by
    # docker, whose daemon runs as root and doesn't need this.
    userns_mode: keep-id
```

```sh
podman compose up --build
# or: podman-compose up --build
```

## Verify

```sh
curl -s -X POST http://localhost:3030/ds/transactions -d op=begin -d type=write
# => {"txn":"<uuid>","type":"WRITE"}
```

If you instead get a 404, the dataset isn't TDB2-backed (check the container
command uses `--tdb2 --loc ... /ds`, not `--mem /ds`), or the module jar isn't
on the classpath -- check `podman logs <container>` for the module's own
startup line, `Added 'transactions' endpoint for dataset '/ds'`, which
`FMod_Transactions.configured()` logs when it successfully wires itself up.

See the main [README.md](README.md) for the full curl walkthrough (update,
query-inside-transaction, isolation from the ordinary `/ds/query` endpoint,
commit/abort).

## Dynamic datasets added via `/$/datasets`

Everything above assumes the dataset (`/ds`) is named on the server's launch
command line, so it exists before the server starts. Fuseki also supports
adding datasets *after* startup through its admin API
(`POST /$/datasets`), and that path has friction points specific to this
module that are easy to lose an afternoon to. Reported from a real
integration; not independently re-verified in this repo's own sandboxed
environment (see CLAUDE.md's Containers/Podman section for why), so treat
this as a documented starting point, not a guarantee.

**A dataset added dynamically does not automatically get the `transactions`
endpoint.** `FMod_Transactions.configured()` (see `FMod_Transactions.java`)
only walks the `DataAccessPointRegistry` *once*, at server-build time --
that's the hook `FusekiAutoModule` gives a module, and there's no equivalent
per-dataset hook that fires again when `/$/datasets` adds one later. The
fix is to wire the endpoint yourself, directly in the dataset assembler
Turtle you `POST` to `/$/datasets`, by adding an explicit `fuseki:endpoint`
block naming this module's operation:

```turtle
fuseki:endpoint [
    fuseki:operation <http://jena.apache.org/fuseki/modules/transactions#transactions> ;
    fuseki:name "transactions"
] ;
```

(That operation IRI is `FMod_Transactions.TRANSACTIONS`'s own identifier --
it's stable, not something to guess at; check the constant in
`FMod_Transactions.java` if it's ever unclear which string to use.)

**Config-file uploads to `/$/datasets` are disabled by default.** Posting a
Turtle assembler document (rather than the simpler form-parameter dataset
creation) fails with `Service configuration from an upload file not
supported` unless the server was started with:

```sh
-Dfuseki:allowAddByConfigFile=true
```

This is a stock Fuseki safety default, unrelated to this module specifically
-- but it's exactly the path you need if you're adding the `fuseki:endpoint`
block above, since that requires posting a full assembler document rather
than the simple form fields. Add it via `JAVA_OPTIONS` in this module's
container (see "How Jena's own Fuseki image works" above for where that env
var is read), or as a plain `-D` argument if running the jar directly.

**The admin API may reject requests from outside the container, even with
the port published.** Fuseki's default Shiro configuration restricts
`/$/**` (the admin/datasets API) to `localhost` requests -- checked at the
HTTP layer, not just "same machine" -- so a request arriving through a
published `podman`/`docker` port from the host (or anywhere else off-box)
gets `403 Access denied : only localhost access allowed`, even though
`curl http://localhost:3030/$/datasets` from *inside* the container would
succeed. This is Fuseki's own admin-auth behavior, implemented by its
built-in `FMod_Shiro` module and a `shiro.ini` policy file (checked directly
in `jena-fuseki-main` -- real classes/strings, not assumed), not something
this module changes. The base image accepts a `--shiro <path>` server
argument (or a `FUSEKI_SHIRO` env var) naming an alternate `shiro.ini`; for
local development, mount one into the container that widens the default
`localhost`-only rule for the `/$/**` admin paths, and point the entrypoint
at it. Treat this the same as any other admin-surface exposure decision --
fine for local dev, not something to carry into a real deployment without
thinking through who else can reach that port.
