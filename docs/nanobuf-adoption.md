# Replacing JSON with nanobuf in the release pipeline and the installers

*Analysis, 2026-10-22.* The goal: no JSON anywhere from preflight to a user's finished install, on the devops side
(`vexelray-dist`, `vexelray-installer`, `vexelray-build`) or on the user's machine (the generated installers,
`oneline-install`). [Nanobuf](nanobuf.md) replaces it. Every file below was found by reading the repos as they stand
today. Line numbers are as of this date.

## The short version

- **Most of the JSON is ours, and much of it is already flat.** Journals, progress events, the manager's state, the
  pinned preflight suite and the scripts' embedded config are flat records or lists of flat records. These are a
  mechanical swap.
- **Three documents are nested and central:** the app manifest (`apps/<id>/manifest.json`), the install record
  (`<id>.json`) and the authoring configs (`installer.json`, `installer-suite.json`). Each needs a nanobuf schema laid
  out by key: rows such as `version` → `bin` → `icon`. `LinuxTarget` already does this flattening for
  `manifest.linux.txt` (`LinuxTarget.java:303-390`), which is nanobuf in all but its separators.
- **Java has to read nanobuf.** The installer CLI, `framework-shell`'s `Apps.java` and `vex-suite-common`'s
  `Install`/`Release` read these documents today, through Jackson and two separate hand-written JSON parsers (about
  155 and 238 lines). A dependency-free Java nanobuf reader replaces all three, at about a tenth of the size.
- **The users' machines in the field set the order of the work.** Installed `update.ps1` scripts and shipped apps
  fetch `manifest.json` and read `<id>.json`. The new format has to be published alongside the old until those
  installs have updated themselves. JSON goes from the pipeline in one release, and from what we serve only after a
  declared transition.
- **What's left is external, and every piece has a way out but one.** GitHub's REST API, GraalVM's reachability
  metadata, msdf-atlas-gen's output and `cargo metadata` each have a non-JSON route, listed below. Khronos's Vulkan
  layer manifests (read by `diagnose.ps1`) and its SPIR-V grammar (vendored into `supirvast`) do not. Both sit at
  the edge of scope: one is a diagnostic, the other an input to a library's code generation.
- **One decision is yours:** what people hand-edit. Nanobuf can't be typed in an ordinary editor, so `installer.json`
  and `installer-suite.json` need a call. See [Hand-written files](#hand-written-files-the-one-real-decision).

## Inventory

### Ours and flat: a direct swap

| what | written by | read by | replace with |
|---|---|---|---|
| Windows install journal `<id>.journal` (JSON lines: `begin`, `put`, `dir`, `path`, `assoc`, `commit`) | `install.ps1:863-865` | `install.ps1:936-938` (recover, rollback), `uninstall.ps1` | One nanobuf row per op. `assoc`'s nested `a` object becomes that row's fields. |
| `##vi {json}` progress events on stdout | `install.ps1:34-40` | `manager.ps1:251-260` | Nanobuf records on the pipe: `step`, `phase`, `progress` rows. |
| `manager\manager.json` `{rev, shortcut}` | `install.ps1:1073-1090` | `install.ps1:1075`, `uninstall.ps1:218-220` | One row. |
| Embedded `CONFIG_JSON` in every generated `.ps1` | `InstallerGenerator.java:134`, `SuiteGenerator.java:275-294` | `'{{CONFIG_JSON}}' \| ConvertFrom-Json` in each template | **Not nanobuf: a PowerShell hashtable literal**, as `oneline-install` already emits (`build.ps1:220`) and as Linux already does with `CONFIG_SH`. Control bytes inside script source are fragile (see [Pitfalls](#pitfalls-found-on-the-way)). |
| `-Requires (… \| ConvertTo-Json)` from install to diagnose | `install.ps1:1203` | `diagnose.ps1:981` | Pass the object. JSON is used only as an in-process argument here. |
| Diagnose probe result `probe.json` | child `powershell.exe` (`diagnose.ps1:633-650`) | parent diagnose | Nanobuf rows (`adapter`, `device`, `queue`, `extension` keyed by device). The child exists to isolate crashes, so any serialization works. |
| `diagnose.ps1 -Json` report | `diagnose.ps1:1005-1010` | a person or a bug tracker | Nanobuf, or drop the switch: the default report is already text. |
| Pinned preflight suite `logs/preflight-suite.json` | `deploy.ps1:90-107` (ConvertTo-Json); `local-linux.sh:52-88` (string concatenation with **no escaping**, then checked with Python) | installer CLI `--suite` | Nanobuf `suite` + `member` rows written with `nbw`. This removes the Python and the unescaped heredoc. |
| `manifest.linux.txt` (tab-separated, `-` for empty) | `LinuxTarget.java:335-390` | `install.sh`, `update.sh`, `suite/install.sh` | Folds into the one nanobuf manifest (next table). |
| Linux `<id>.state` and journal (tab-separated) | `install.sh` | `install.sh`, `update.sh`, `uninstall.sh` | Not JSON, but they move to nanobuf so one record serves Linux, Windows and Java. Values could then hold tabs, which `install.sh:519` refuses today. |

### Ours and nested: needs a schema

**App manifest** `apps/<id>/manifest.json`. This is the central document.

- Written by `InstallerGenerator.java:151-199`, which merges history, with platform entries from `WindowsTarget` and
  `LinuxTarget`.
- Read by:
  - the scripts: `preflight.ps1:142`, generated `install.ps1:1113`, `update.ps1:54`, `manager.ps1:135`, suite
    `install.ps1:97`;
  - the Linux side, through `manifest.linux.txt`;
  - apps in the field: `vex-suite-common` `Release.java:25-63` and `Updates.java:192-231`.
- The shape is 5 to 6 levels deep: app, then `tools{}`, then `versions[]`, then `platforms.{windows,linux}`, then
  `requires`, `binaries[]` and, under each binary, `icon`, `fileIcons[]`, `fileAssociations[]`, `exposes{}`, `jvmArgs[]`
  and `signature`.

As nanobuf, every map becomes rows keyed by name, and every list becomes repeated rows that carry their parent's key:

```
nanobuf␟1␟vexelray.AppManifest␟1.0␞
app␟calculator␟Calculator␟sibarum␟…␟1.4.2␞             latest is a field
tool␟install.ps1␟https://…␟5e3b…␞
version␟1.4.2␟2026-10-01␟…␞
platform␟1.4.2␟windows␟%LOCALAPPDATA%\Programs\Calculator␞
requires␟1.4.2␟windows␟vexelray-runtime␟1.2.0␞
bin␟1.4.2␟windows␟calculator␟native␟https://…␟91ac…␟5242880␟bin/calculator.exe␟1␞
jvmarg␟1.4.2␟windows␟calculator␟-Xss4m␞                 order kept: rows of one kind keep their order
assoc␟1.4.2␟windows␟calculator␟.calc␟Vexel.Calc␟…␞
```

The same file serves both platforms, so it replaces both `manifest.json` and `manifest.linux.txt`. That deletes about
55 lines of `LinuxTarget` that exist only because "sh cannot read JSON". The composite keys (version, platform, binary)
are verbose, but no reader has to resolve anything.

**Install record** `<registryDir>/<id>.json` plus `<installDir>/install-manifest.json`. This is the contract between
the installers and the apps.

- Written by Windows `install.ps1:1452-1480` (ConvertTo-Json) and Linux `install.sh:124-149`. The Linux writer is a
  hand-written awk JSON emitter that escapes only `\` and `"`, so a control character in a path produces invalid JSON.
- Read by:
  - every Windows maintenance script (update, uninstall, manager, diagnose, suite install), and
    `release-preflight.ps1:169`;
  - in Java: `framework-shell` `Apps.java:77-87`, with its own 155-line parser, and `vex-suite-common`
    `Install.java:71-107`, through `vexelray-text` `Json.java`, 238 lines.
- `README.section.md:41-48` documents it as public. Whether third parties count is your call: everything that reads
  it today is ours.

As nanobuf it is flat rows: `install` (id, name, version, installDir, manifestUrl, …), `command`, `archive`, `file`,
`shortcut`, `path`, `assoc`, `option` and `registry`. On Linux the one record replaces both `.state` and the JSON.

**Authoring configs.** `<app>/installer.json` (9 apps) and `installer-suite.json` (whose `site{}` holds the landing
page's links, sections, cards and footer). They are hand-edited, and read by:

- the installer CLI, through Jackson with fail-on-unknown, mirrored by 22 POJOs (about 350 lines);
- `preflight.ps1`, `release-preflight.ps1` and `deploy.ps1`;
- `build.sh:47-49`, by grep and sed;
- `notices.py`, through `json.load`.

`release-preflight.ps1:105-111` changes the version with a regex that assumes the first `"version"` in the file is the
top-level one. All of these become `nbr` and `nbw` calls, or the Java reader, once the format question below is
settled.

### External: JSON that something else dictates

| what | where | way out |
|---|---|---|
| GitHub Releases REST API, **devops** side | `GithubReleasePublisher.java:43-100` | Have `gh release create` / `gh release upload --clobber` do it, so no JSON appears in our code; JSON still flows underneath. Or declare the HTTP client a boundary and keep about 18 lines of JSON in one class. |
| GitHub Releases REST API, **user's machine** | `oneline-install/templates/windows/install.ps1:28-53` (asset URL, tag and `digest`) | **Don't call the API.** `https://github.com/<repo>/releases/latest/download/<asset>` redirects to the asset, and a `SHA256SUMS.nb` published beside it carries the digest. This also gets rid of the unauthenticated rate limit (60 an hour) and the "no checksum when rate-limited" fallback. Strictly better. |
| GraalVM reachability metadata: about 35 committed `reachability-metadata.json` files, plus the framework processor's generated one (`Generator.java:741-754`) | native-image | A `org.graalvm.nativeimage.hosted.Feature` registered through `--features=` in `native-image.properties` does the same in Java. `RuntimeReflection`, `RuntimeJNIAccess`, `RuntimeForeignAccess` and `RuntimeResourceAccess` cover reflection, JNI, foreign calls and resources. The framework processor would generate a Feature class instead of JSON. This is the biggest piece of work with no format benefit: our code never parses these files. |
| msdf-atlas-gen `-json` output (`GenerateAtlasMojo.java:220`), parsed by regex in `NotdefGlyph`, `FontManifest` and `CharsetCoverage` (about 70 lines) | atlas build | The tool also writes `-csv` glyph geometry. The metrics the regex reads (size, distance range, dimensions) are arguments we pass it. Write the atlas metadata and `fonts.json` as nanobuf. Note that `vexelray-text` reads these at app runtime, so they reach users. |
| `cargo metadata --format-version 1` (`notices.py:228`) | notices | `cargo tree -e normal --prefix none --format '{p}\t{l}'` gives name, version, source and license as text. Licence files come from the registry source directory. |
| Khronos SPIR-V grammar (`supirvast/vastir-codegen`, Gson at generate-sources) | library build | No non-JSON form upstream. Either keep it as a declared input of one library's code generation, or convert it once with a committed converter and re-run on upgrades. |
| Vulkan implicit-layer manifests (`diagnose.ps1:735-750`) | user's machine, diagnostics only | None: GPU vendors install them. `ConvertFrom-Json` is built into the shell, so this costs us no code. Keep it as a declared exception. |
| Manager's loopback HTTP API to its Edge page (`manager.ps1:559-803`) | user's machine | Not really dictated: the page can read nanobuf in one line of JavaScript (`text.split('\x1e').map(r => r.replace(/^\n/, '').split('\x1f'))`). That makes a JavaScript entry for the golf course. |

### Not consumed by the pipeline

`META-INF/intrabuf/<module>.json` manifests are written into every jar that uses intrabuf, but nothing outside
intrabuf's own tests reads them. No pom sets `-Aintrabuf.history`, and nothing calls `intrabuf freeze`. These are
JSON in build output, not in the process. Moving them is intrabuf's own business and can wait.

## Hand-written files: the one real decision

`installer.json` and `installer-suite.json` are written by people. A nanobuf file can be read in an editor (the writer
puts each record on its own line), but `0x1F` can't be typed, and editors and terminals render it inconsistently or
not at all. Three ways to go:

1. **Nanobuf, edited by a tool.** `vexelray-installer config set calculator version 1.4.3` and the like. Purest, and
   the release scripts already make their edits by machine (`release-preflight.ps1`). Adding an app or a landing-page
   card by hand becomes a command, though.
2. **A line-per-row authoring syntax that a build step turns into nanobuf.** For example `version 1.4.2 2026-10-01`
   with tab or `=` separators, read only by the installer CLI, which writes the `.nb` that every script reads. This
   is two formats, but only one is ever parsed by a shell.
3. **Keep JSON as the authoring format, read only by the CLI,** which writes a resolved nanobuf for everything
   downstream. This removes JSON from every script, but not from the repository, so it doesn't meet the goal as
   stated.

I'd take 1 for `installer.json`, which is small and mostly changed by the release scripts already. I'd take 2 for
`installer-suite.json`, whose `site{}` is landing-page copy that people do write by hand.

## What Java needs

- **A reader and writer in `intrabuf-nanobuf`'s main code.** The test `Reference` is about 70 lines and dependency-free.
  That fits `framework-shell`, whose JSON parser exists only "because nothing on this stack carries a JSON library".
  It replaces Jackson in the installer CLI for our own formats, `Apps.java`'s private parser, and `vexelray-text`
  `Json.java` for the install record and the manifest.
- **Later, processor-generated codecs** for flat `@IntrabufBundle` schemas, as [nanobuf.md](nanobuf.md) proposes. The
  schemas above (manifest, record, suite, journal) would then be declared once, as records, and Koine's compiler could
  generate its typed readers from the same declaration.
- **Koine** lowers `ReadJson`/`Field` to `jq` forks on Bash and Zsh, and to `ConvertFrom-Json` on PowerShell
  (`BashLowering`, `ZshLowering`, `PsLowering`). Its expected `preflight.sh` has dozens of `jq` calls. Its planned
  `Nanobuf` module can paste `NanobufLine` code directly. Once the inventory above is converted, it needs `Json.read`
  only for the external files, and an installer needs it for none of them.

## The installed base

A user who installed last month has an `update.ps1` that fetches `manifest.json` and reads `<id>.json`, and apps
whose update check (`Release.java`) parses `manifest.json`. Changing the format breaks them unless:

1. **Release N** writes `manifest.nb` and still writes `manifest.json`. Its scripts and apps read the `.nb`. Its
   installer and updater read an existing `<id>.json` once, write `<id>.nb`, and delete the JSON. That one-time read
   is a `ConvertFrom-Json` on Windows. Linux has nothing to read: `.state` holds everything.
2. **Release N + k**, once you declare the oldest supported install (the same idea as an endpoint's `oldestPeer`),
   stops writing `manifest.json`. From then on, anything older can't update itself and needs a reinstall.

The pipeline itself (preflight, build, deploy) has no installed base, so it can switch in one release.

## Pitfalls found on the way

- **Line endings.** With this stack's `* text=auto`, Git checks a nanobuf file out on Windows with CR LF. Readers that
  dropped only LF then saw every kind as `\r\nversion` and silently skipped every row (reproduced with `read.bash`).
  The spec now has readers drop any one line break after a record (CR LF first, then LF or CR), so the rows survive.
  Line breaks inside values still come back as CR LF, so files whose values hold them want `*.nb eol=lf`, as
  `vexelray-dist` already does for its manifests (`.gitattributes:7`). Also, gawk on Windows reads in text mode and
  turns CR LF into LF unless `BINMODE=3`; `read.sh` sets it.
- **Git for Windows' bash drops carriage returns from a script's own text,** even inside quotes. This is one more
  reason not to embed data in generated scripts: pass it as a file, or as native literals with no CR.
- **Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI.** This is a live bug today, independent of nanobuf. The
  templates write records and journals without a BOM, then read them back with `Get-Content -Raw` and no `-Encoding`
  (`install.ps1:275, 937, 1141`, `update.ps1:51`, `uninstall.ps1:92`, `manager.ps1:93`, `diagnose.ps1:665, 772`, suite
  `install.ps1:59`). Reproduced on 5.1.26100: `C:\Users\Zoë\Apps` reads back as `C:\Users\ZoÃ«\Apps`. `read.ps1` uses
  `ReadAllText` with explicit UTF-8, so the switch fixes this, but it is worth fixing before the migration.
- **Fragile parsing that goes away:** `build.sh:47-49` (grep and sed over JSON), `release-preflight.ps1:105-111`
  (regex rewrite), `local-linux.sh:52-88` (JSON by string concatenation), the Linux awk JSON writer, and the msdf
  regex parsing.

## Order of work

| step | scope | size |
|---|---|---|
| 0 | `intrabuf-nanobuf`: the shell lines and their tests | done |
| 1 | Java reader and writer in `intrabuf-nanobuf`; decide the authoring format; `.gitattributes` in every repo with `.nb` files | small |
| 2 | **Devops:** authoring configs, pinned suite, `preflight.ps1`, `release-preflight.ps1`, `deploy.ps1`, `build.sh`, `local-linux.sh`, `notices.py` (`cargo tree`); the installer CLI reads nanobuf in place of Jackson | medium: about 400 lines of Java POJOs and tree code, about 70 lines of script |
| 3 | **User's machine:** one `manifest.nb`, the install record, journal, events, `manager.json`, hashtable config literals; `oneline-install` through redirect URLs and `SHA256SUMS.nb`; dual-publish `manifest.json` | large: all six Windows templates, three Linux templates, `WindowsTarget`/`LinuxTarget`, `Apps.java`, `Install`/`Release` |
| 4 | **Build exceptions:** GraalVM metadata into generated Features; msdf `-csv`; the GitHub publisher onto `gh` (or declared a boundary) | medium, mostly mechanical |
| 5 | Stop publishing `manifest.json` at the declared oldest install | one line, later |

After step 4, the only JSON left is declared: Khronos's two files, the GitHub API if it stays a boundary, and the
one-time read of old records during the transition.
