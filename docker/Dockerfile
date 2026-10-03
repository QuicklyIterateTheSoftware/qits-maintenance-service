# qits-platform-maintenance as a GraalVM-native container IMAGE — the same shape as every other qits
# service's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal runtime stage
# carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names
# 25.0.2-graalce, so `sdk env && ./mvnw package -Dnative` produces service/target/qits-platform-maintenance
# locally with no container involved. What this adds is the image around it — and a way for a machine
# with no GraalVM to get a binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/qits-platform-maintenance:latest -f docker/Dockerfile .
#
# THERE IS A CLIENT NOW, and it is NOT built here — it arrives in the build context already built.
# The whole of why is with the mvnw line below, where the five flags that arrange it live. A
# host-side build therefore needs `git submodule update --init` and then `npm ci && npm run build`
# in service/src/main/webui before this command; the CI recipe does both in its first half.
#
# --- `docker run` with no configuration FAILS, and that is the honest behaviour -------------------
# The `maintenance` jar's datasource is an EXPRESSION over the platform's generic resource contract
# — `jdbc.url=${QITS_RESOURCE_DB_URL}` and its username/password siblings — with no fallback url at
# all. Unset, the expressions are unresolvable and the process dies during Flyway's first connect
# naming the missing variable.
#
# That is a missing-configuration failure, not a packaging one, and it is deliberately NOT patched
# here with an `ENV`: no address this image could name would be right on a real platform, and a
# default would only make the bare run "work" against a store nobody meant. It matters more here than
# in most services — this database holds the bump log, the only record of which branches this
# service pushed and on the strength of which pins — so opening the wrong one silently would be the
# worst failure this image could have. What a deployment supplies instead is the triple:
#
#     QITS_RESOURCE_DB_URL=jdbc:postgresql://<tier>-qits-oci-postgresql:5432/qits_platform_maintenance
#     QITS_RESOURCE_DB_USERNAME=qits_platform_maintenance
#     QITS_RESOURCE_DB_PASSWORD=…
#
# and nobody types those: `.config/qits/deployments.yml` declares `resources: postgresql:db`, and
# qits-platform-deployments creates the role and the database before this container starts and
# injects all three. This image carries no volume for data — the store is another container's.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds service's dependency from source —
# `qits-platform-maintenance-domain` is a version this repo never publishes, so a builder container with an
# empty ~/.m2 has nowhere else to get it. Copying module by module would mean editing this file every
# time one is added, and forgetting to would fail the build with an unresolved dependency rather than
# anything that names the real cause. `.dockerignore` is what keeps this cheap.
COPY . .

# --- where the three qits jars come from ----------------------------------------------------------
# This repo depends on qits-db-core (PatientPgDriver, DbRetry), qits-auth-core (the forward-auth
# mechanism and the machine gate) and qits-arch-rules, which live only on the platform's own Maven
# repository. Both platform hosts are code plus one input, the public domain:
#
#   https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven   hosted Maven (qits-artifacts)
#   https://mirror.qits.$QITS_DOMAIN/mirror/maven/central      Maven Central cache (qits-mirror)
#
# The RUN below derives both and exports them for that RUN alone; `.qits-maven-settings.xml` reads
# them as ${env.…}, mirroring the `qits-maven` repository id onto the first and Central onto the
# second. The CI recipe passes QITS_DOMAIN; the default is the live platform's.
ARG QITS_DOMAIN=wohlben.eu

# --- and how Maven authenticates ------------------------------------------------------------------
# Both hosts answer 401 anonymously, so every build that resolves through them needs a credential.
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is why they are not build args: this image
# is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` reads the two variables as the `qits-maven-network` server.
# The `RUN` reads the two mounted files itself: docker 26's BuildKit has no `env=` mount option.
# FILE form (`/run/secrets/<id>`, read into the command's own environment), not the `env=` mount
# form: env= needs a dockerfile frontend the builder chooses, while the bare file mount is served
# identically by every frontend version — and under the platform builder the frontend is the
# builder's, not this file's, to pick. Absent secrets leave both variables empty (`|| true` is every
# hand build's state), which the settings read exactly as they read unset.
#
# -Dnative flips quarkus.native.enabled (service/pom.xml carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# -DskipTests skips execution, not compilation: test sources still compile here. Running them is what
# is not possible in this stage — the suite spawns an embedded postgres, and PackagedSurfaceIT (which
# the `native` profile un-skips) launches the binary against one. `./mvnw verify` on a clone is the
# gate for those.
#
# --- node, and the three command overrides: why the client is NOT built here ----------------------
# The two `package-manager-install` flags give this stage a node: the Mandrel builder image ships
# none at all, and Quinoa shells out to npm regardless. They live here rather than in
# application.properties on purpose — a local or CI `mvnw` build must use the node on PATH, so that
# no build silently downloads a toolchain. The version is the PLATFORM pin (22.22.0), and it does not
# have to match the step container's node, because this stage does not build the bundle.
#
# service/src/main/webui is qits-platform-spa-maintenance, and it depends on @qits/ui-components. That
# package exists ONLY on the platform's own npm registry (qits-artifacts). A RUN step in a docker
# build reaches the public internet — Maven Central, npmjs.org, nodejs.org — and reaches the platform
# registry by NO address: not the qits-net alias, not registry.dev.localhost:8080, not
# host.docker.internal. So the install cannot happen here.
#
# THE FIRST HALF OF THAT IS NO LONGER STRICTLY TRUE UNDER THE PLATFORM BUILDER — a RUN executes on
# qits-net there, where the registry's alias does resolve — and the arrangement still does not move.
# The SPA's committed .npmrc and its lockfile both name edge vhosts, and the origin swap that
# corrects them is the CI step's business, not this stage's; and this stage has to keep building on a
# developer's host, where nothing platform-side resolves at all.
#
# It does not merely fail, it fails unreadably. The SPA commits an .npmrc pointing at the edge's
# registry vhosts (right for a developer on the deployment host), npm ranks a project .npmrc above
# ~/.npmrc, and npm's answer to a registry that never connects is `Exit handler never called!` — its
# own internal-error text, naming neither the registry nor the address.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry is reachable, and
# arrives in the build context as service/src/main/webui/dist/ (see .dockerignore, which stops short
# of excluding exactly that path for this reason). `--version` is the null command: it proves the
# package manager is really there and does nothing else.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle before the
# multi-minute native compile, and Quinoa's own build-dir check would stop it after. A forgotten
# `npm run build` in the step is a red build, not a service that ships without its client. The same
# guard covers a context whose webui submodule is NOT initialised: it has no bundle either.
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-platform-spa-maintenance/browser`, which names the source and
# explains nothing. A builder stage that *creates* dist itself is immune by accident, because dist is
# then already in the upper layer; ours arrives by COPY, so it is not.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    d=service/src/main/webui/dist/qits-platform-spa-maintenance/browser; \
    test -f "$d/index.html" || { \
      echo "No built client SPA at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# makeBom RIDES ON THE INVOCATION ABOVE rather than getting a second one, for the same reason
# everything else in this RUN is one instruction: a second `./mvnw` would be a second layer over a
# native compile, and a re-resolve of the same reactor to produce a document the first run already
# had the model for.
#
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured in qits-artifacts-service on its first
# release: `service` is <packaging>quarkus</packaging> and ships as this image rather than as a jar,
# so it skips maven-deploy — and the plugin SKIPS deploy-skipping modules by default, with a green
# build and one INFO line. The sbom stage's COPY then fails on a file nothing wrote. The spelling
# matters too: a bare -DskipNotDeployed=false is silently ignored, and only the cyclonedx.-prefixed
# property reaches the plugin.

# --- sbom -----------------------------------------------------------------------------------------
# The service module's CycloneDX document, exported so the release step can PUT it to the platform's
# SBOM store. A stage rather than a layer of the runtime image, and BEFORE the runtime stage on
# purpose: the last stage is what a plain `docker build` builds, and that must stay the image.
# `.config/qits/ci-event-release.yml` reaches this one with `--opt target=sbom --output type=local`
# using the SAME args and secrets as the image build, so every layer above is a cache hit and the
# export costs seconds rather than a second native compile.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# Nothing is installed here. This service shells out to nothing at all — the binary and libc are the
# whole runtime. If that ever stops being true, the missing executable will surface as an IOException
# at the first request rather than at build time, so add the package in the same commit as the code
# that forks it.

# `quarkus.package.output-name` names the binary `qits-platform-maintenance` (no -runner suffix), so the
# process is identifiable in `ps`/`docker top`. It is built by `service`, the one module of the two
# that is an application; `maintenance` is a library jar with nothing to native-compile.
COPY --from=build --chown=1001:root /src/service/target/qits-platform-maintenance /work/qits-platform-maintenance
RUN chmod +x /work/qits-platform-maintenance

# NO MOUNT POINT FOR DATA. The store is another container's: this service writes nothing to disk that
# outlives it, so there is no volume to own and none to back up. The durability this component exists
# to provide is the postgres one.

EXPOSE 8080
USER 1001

# Everything this service serves hangs off /maintenance — the client at /maintenance/, the API at
# /maintenance/api/* and health, openapi and swagger-ui at /maintenance/q/* — because qits-gateway
# routes verbatim by prefix. The paths are in application.properties, not here; see README.md.
ENTRYPOINT ["/work/qits-platform-maintenance", "-Dquarkus.http.host=0.0.0.0"]
