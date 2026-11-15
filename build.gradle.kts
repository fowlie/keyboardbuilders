val kotlinVersion = "2.2.21"
val jteVersion = "3.2.4"

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.7"
	id("io.spring.dependency-management") version "1.1.7"
	id("gg.jte.gradle") version "3.2.4"
	// Line coverage, measured rather than assumed. `./gradlew koverHtmlReport` writes
	// build/reports/kover/html/index.html; koverXmlReport is the machine-readable one.
	//
	// NO THRESHOLD, deliberately. A `koverVerify` bound turns a number into a gate, and
	// the gate is what makes people write tests that execute lines instead of tests that
	// check behaviour. The number is here to be looked at when deciding where to spend an
	// evening, and nothing about it should fail a build until someone decides it should.
	//
	// The jte-generated template classes are counted. That is on purpose: it is the only
	// signal that a template was rendered at all, and an unrendered template is a 500
	// waiting for the first reader (see application-prod.properties on the dev-profile
	// trap, which fails at first render rather than at boot).
	id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

group = "net.keyboardbuilders"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(24)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// PLAIN JDBC, NOT SPRING DATA JDBC. This project queries through `JdbcClient`, which
	// lives in spring-jdbc; the Spring Data starter additionally brought repository
	// auto-configuration, which needs a `Dialect` and has none for SQLite. That failed
	// the app on every devtools restart -- "Couldn't determine Dialect for sqlite",
	// "Application run failed" -- while the first boot succeeded, so it read as flaky
	// rather than broken. The repository scan reported "Found 0 JDBC repository
	// interfaces" on every boot, which is the whole story: nothing here ever used it.
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	// Health and metrics for StatLite to poll. Forgejo #28.
	//
	// SAFE ONLY BECAUSE IT IS ON ITS OWN PORT. `show-details=always` makes
	// /actuator/health report component-level state -- the datasource among them -- and
	// /actuator/metrics enumerates the app's internals. See application-prod.properties:
	// the management server binds loopback on a port the proxy does not publish, and
	// Caddy refuses /actuator* as well, so a future config change cannot quietly expose it.
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// Sign in with GitHub, backlog #116. GitHub only, as product.md 7 decided: no password
	// column exists, so there is no reset flow, hashing or credential-stuffing surface.
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
	implementation("gg.jte:jte-spring-boot-starter-4:$jteVersion")
	implementation("io.github.wimdeblauwe:htmx-spring-boot:5.1.0")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	// Backlog #117: the bot's questions are Markdown (the same text the GitHub issues held).
	// HTML in them is escaped, since they quote READMEs written by strangers.
	implementation("org.commonmark:commonmark:0.24.0")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	// Lets jte compile .kte templates at runtime under the `dev` profile, so a
	// template edit is a browser refresh instead of a rebuild. `developmentOnly`
	// keeps the Kotlin compiler -- ~50MB of it -- out of the shipped jar.
	developmentOnly("gg.jte:jte-kotlin:$jteVersion")
	// jte-kotlin asks for 2.2.20; pinned up to the version the rest of the build
	// already uses, so there is one Kotlin compiler in the dependency graph.
	developmentOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
	runtimeOnly("org.xerial:sqlite-jdbc")
	// Issue #2 argued against a migration tool: the deployed database is a freshly
	// built file every time, so there is never data to evolve. The dev loop is the
	// case it did not weigh -- `app.db` persists across restarts, and plain
	// `spring.sql.init` re-runs its DDL on every boot and fails on existing tables.
	// Flyway's history table makes application idempotent, which is the need the
	// issue's own "deferred, not banned" clause was left open for.
	implementation("org.flywaydb:flyway-core")
	// Spring Boot 4 split per-technology autoconfiguration out of
	// `spring-boot-autoconfigure`. Without this module Flyway sits on the classpath and
	// does nothing at all -- no log line, no schema history, no error. Boot 3 needed
	// only flyway-core, so this is the line a 3.x example will not have.
	implementation("org.springframework.boot:spring-boot-flyway")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

jte {
	generate()
	binaryStaticContent = true
	// Without this every @for/@if leaves its own blank line in the response. The
	// property `gg.jte.trim-control-structures` only applies to runtime compilation,
	// so for precompiled templates the switch has to be here.
	trimControlStructures = true
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// The `dev` profile is what turns on runtime template compilation. Only `bootRun`
// gets it: tests and the packaged jar use the precompiled classes, which is also the
// only configuration that has no Kotlin compiler available to fall back on.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	systemProperty("spring.profiles.active", "dev")
}
