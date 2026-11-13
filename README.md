# keyboardbuilders.net

A searchable catalogue of open-source DIY keyboard designs: <https://keyboardbuilders.net>.

Each design links to its source repo and credits its designer. Most of the facts on a
design page, such as layout, switch footprint, controller and stagger, are read from the
design's own files: its board files, firmware and README.

## Run it

You need a JDK, any version from 17 up, to start Gradle. Gradle fetches JDK 24 for the
build itself.

```sh
./gradlew bootRun     # http://localhost:8080, with templates reloading on save
./gradlew test
```

`bootRun` uses the `dev` profile: it builds a SQLite database from
`src/main/resources/seed/designs.json` on start, and lets you act as any designer at
`/dev/sign-in-as?login=<github login>`, with no GitHub app needed.

## What is where

| | |
|---|---|
| `src/main/kotlin` | the Spring Boot app (Kotlin) |
| `src/main/jte` | page templates ([jte](https://jte.gg)) |
| `seed/` | hand-written facts and corrections, as TOML |
| `scripts/` | the Python that scans design repos and builds `designs.json` (standard library only; tests: `cd scripts && python3 -m unittest test_*.py`) |
| `data/` | what the scans found, committed so a change shows up in a diff |
| `deploy/` | how the live site runs |

Comments often cite "ticket 22", "backlog #104" or "Forgejo #49". Those refer to the
maintainer's notes and to the issue tracker the project used before GitHub, neither of
which is public. A bare "#49" in a GitHub issue is a different thing.

## Contributing

Corrections to a design are the most useful thing: open an issue saying what is wrong and
where the design's repo shows it. Designers can sign in on the site and unpublish their own
designs.

## Licence

The code is under the GNU Affero General Public License v3 (see `LICENSE`). The design
summaries quote each designer's README and stay under their terms (see `NOTICE`).
