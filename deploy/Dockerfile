# The runtime image for keyboardbuilders.net. Backlog #71.
#
# RUNTIME ONLY -- the jar is built by the workflow and copied in, rather than built here
# in a multi-stage image. That is deliberate: a Gradle build inside `docker build` cannot
# use the runner's Gradle cache without extra plumbing, and this project's build is the
# slow part. The workflow already has a JDK and a warm cache; docker's job is to package.
#
# DEBIAN, NOT ALPINE, AND THE REASON IS ON DISK IN deploy/README.md. The share cards at
# /designs/:slug/og.png are drawn with Java2D, which needs real font libraries; without
# them every card is a 500 while every page renders fine, so nothing looks broken until a
# crawler fetches an image. The README's own fix names these four packages for the host,
# and an image is just a smaller host. Alpine would mean musl plus a different font stack
# to re-verify for no benefit an 80 MB saving justifies.
# FULLY QUALIFIED, and it is not decoration. Docker silently assumes `docker.io/library/`
# for a bare name; podman refuses to guess, because which registry a short name resolves to
# is a security question -- a machine with a different `containers-registries.conf` would
# build this from somewhere else entirely. The CI build failed here on its first attempt
# with "short-name did not resolve to an alias". Spelling it out works identically under
# Docker, so this is the portable form rather than a podman workaround.
FROM docker.io/library/eclipse-temurin:25-jre-noble

# The exact four from deploy/README.md's "One OS dependency, and it is not obvious".
# The failure names `libharfbuzz.so.0` and is the whole diagnosis. `curl` is the fifth and
# is for the healthcheck in compose.yml -- which fetches a PAGE and greps it, because
# trap 2 is a failure at first render that any liveness probe sails past.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libharfbuzz0b libfreetype6 libfontconfig1 fonts-dejavu-core \
      curl \
 && rm -rf /var/lib/apt/lists/*

# THE SAME UID AS THE HOST SERVICE ACCOUNT. The database is a bind mount of
# /var/lib/keyboardbuilders, which `backup.sh` reads and the systemd unit's own user
# owns, so a container writing as a different uid leaves files the backup timer cannot
# read and the old unit could not take back. `--system` matches how the host account was
# made; the uid is pinned rather than allocated so a rebuilt image cannot drift off it.
ARG APP_UID=987
RUN groupadd --system --gid ${APP_UID} keyboardbuilders \
 && useradd --system --uid ${APP_UID} --gid ${APP_UID} \
      --home /var/lib/keyboardbuilders --shell /usr/sbin/nologin keyboardbuilders

COPY --chown=root:root keyboardbuilders.jar /opt/keyboardbuilders/keyboardbuilders.jar

USER keyboardbuilders
WORKDIR /opt/keyboardbuilders

# -XX:MaxRAMPercentage and not -Xmx, copied from the systemd unit's reasoning: the box can
# be resized, and a fixed heap on a shrunk box is an OOM kill at 3am. Inside a container
# the JVM reads the CGROUP limit rather than the host's RAM, so this is a percentage of
# whatever `mem_limit` the compose file sets -- which is the behaviour we want and is
# strictly better than the host case, where 75% of 3.8 GB competed with everything else.
ENV JAVA_OPTS=-XX:MaxRAMPercentage=75

# `exec` so the JVM is PID 1 and receives SIGTERM directly -- without it the shell is PID
# 1, swallows the signal, and every deploy is a 10-second timeout followed by SIGKILL,
# which discards `server.shutdown=graceful` and 502s whoever was mid-page.
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /opt/keyboardbuilders/keyboardbuilders.jar"]
