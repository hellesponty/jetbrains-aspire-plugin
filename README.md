# Aspire AppHost Services (JetBrains plugin)

Shows the Aspire AppHost of a repository in the **Services** tool window of JetBrains IDEs other than Rider,
with TypeScript AppHosts (`apphost.mts` / `apphost.ts`) supported first-class.
Rider has JetBrains' own Aspire plugin, so this one declares itself incompatible with Rider and doesn't load there.

## What it does

- Looks for `aspire.config.json` at the project root and reads `appHost.path` / `appHost.language`.
  It also notices when the file is created, edited or deleted.
- Adds an **Aspire** node to Services: **AppHost → resources**. Child resources such as Postgres databases sit under their parent.
- **AppHost node:** Start / Stop / Restart, Open Dashboard, *Restore TypeScript SDK* (`aspire restore`),
  open `aspire.config.json`, Refresh. Its console shows the CLI output plus the logs of every resource, each line tagged with the resource name.
  Double-click opens the AppHost file.
- **Resource nodes:** live state, health and URL, plus a console with that resource's logs (ANSI colours are kept).
  Start / Stop / Restart are enabled from the commands the resource itself offers. There are also Open URL and Open in Dashboard,
  and any custom commands the resource defines.
- It attaches to an AppHost that is already running, including one started from a terminal with `aspire run` or `aspire start`.

## How it works

The plugin has no Aspire-specific protocol code. It drives the `aspire` CLI, which works the same way for every AppHost language:

| Purpose | Command |
|---|---|
| Is our AppHost running? | `aspire ps --format Json --follow` |
| Resource state | `aspire describe --apphost <path> --format Json --follow` |
| Logs | `aspire logs --apphost <path> --format Json --follow --timestamps` |
| Start / stop the AppHost | `aspire start` / `aspire stop --apphost <path>` |
| Resource commands | `aspire resource <name> <start\|stop\|restart\|…> --apphost <path>` |

The CLI is looked up in this order:
1. the path set in **Settings | Tools | Aspire**;
2. `aspire` on the login-shell `PATH`;
3. `~/.aspire/bin/aspire`.

## Build / run

```bash
./gradlew buildPlugin          # -> build/distributions/aspire-apphost-services-*.zip
./gradlew runIde -PopenProject=/path/to/repo-with-aspire.config.json
./gradlew runGoland -PopenProject=/path/to/repo   # same, in GoLand (golandLocalPath)
./gradlew verifyPlugin  # IntelliJ IDEA, WebStorm and GoLand, 2024.3 → latest (downloads ~21 IDEs on first run)
```

Avoid calling interface members of platform/coroutines APIs without their default arguments
(write `job.cancel(null)`, not `job.cancel()`): Kotlin compiles those calls to synthetic `$default`
methods that don't exist in every IDE version, which crashed GoLand 2026.2 with `NoSuchMethodError`.

`gradle.properties` points `platformLocalPath` at a locally installed IntelliJ IDEA, so the IDE isn't downloaded.
Remove that line to download `platformVersion` instead. Install the zip with *Settings | Plugins | ⚙ | Install Plugin from Disk…*.

Source layout (`src/main/kotlin/io/github/hellesponty/aspire`):
- `cli/`: CLI wrapper, JSON models, `aspire.config.json` parsing
- `services/AspireAppHostService.kt`: state, CLI streams, log routing
- `services/AspireServiceViewContributor.kt`: Services tree
- `services/LogSink.kt`: buffered consoles
- `actions/`: toolbar and popup actions
- `settings/`: CLI path setting

## Publishing to JetBrains Marketplace

Rules: [approval guidelines](https://plugins.jetbrains.com/legal/approval-guidelines) ·
[publishing docs](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html) ·
[signing docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

### Before the first release
1. **Vendor:** in `src/main/resources/META-INF/plugin.xml`, replace the `TODO`s in `<vendor>` with your name, a website and an email.
   Both the website and the email must work, and they appear publicly.
2. **Licence:** `LICENSE` (MIT) must be in the public repository linked in the description.
   An open-source licence requires a link to the source code.
3. **Plugin ID:** `io.github.hellesponty.aspire` is final once uploaded. It can never be changed.
4. **Version and notes:** bump `pluginVersion` in `gradle.properties` and update `<change-notes>` for every upload.
   The Marketplace rejects a version that already exists.
5. **Compatibility:** run `./gradlew verifyPlugin`. The guidelines require Plugin Verifier compatibility for every upload.

`signPlugin` and `publishPlugin` refuse to run while `plugin.xml` or `LICENSE` still contain `TODO`.

### Signing key (once)
```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```
Keep these files out of the repository. The build reads the key material from environment variables:
`CERTIFICATE_CHAIN` (contents of `chain.crt`), `PRIVATE_KEY` (contents of `private_encrypted.pem`) and
`PRIVATE_KEY_PASSWORD`. Multi-line values may be Base64-encoded.

### First upload (manual, on the website)
1. Run `./gradlew signPlugin`. This writes `build/distributions/aspire-apphost-services-<version>-signed.zip`.
2. On plugins.jetbrains.com, sign in and choose **Upload plugin**.
   - Accept the Developer Agreement.
   - Create a vendor profile.
   - Choose the licence (MIT) and give the source-code URL.
   - Declare whether you are a trader or non-trader under EEA consumer-protection law.
3. JetBrains reviews every new plugin and every update by hand. Expect about 3–4 working days; after that,
   the guidelines say to contact marketplace@jetbrains.com.

### Later versions (from Gradle)
1. Create a token at https://plugins.jetbrains.com/author/me/tokens. It is shown only once.
2. Bump `pluginVersion`, update `<change-notes>`, and run `./gradlew verifyPlugin`.
3. Run:
   ```bash
   PUBLISH_TOKEN=… CERTIFICATE_CHAIN=… PRIVATE_KEY=… PRIVATE_KEY_PASSWORD=… ./gradlew publishPlugin
   ```
   This signs the plugin first. Each update goes through review again.

Builds on other machines or in CI ignore `platformLocalPath`/`golandLocalPath` when those folders are missing,
and download the IDE instead.
