# Aspire AppHost Services (JetBrains plugin)

Shows the Aspire AppHost of a repository in the **Services** tool window of JetBrains IDEs,
with TypeScript AppHosts (`apphost.mts` / `apphost.ts`) supported first-class.

Please note that since Rider has an official plugin for Aspire support, this plugin does not work on Rider.

## What it does

- Looks for `aspire.config.json` at the project root and reads `appHost.path` / `appHost.language`. 
  It also watches for changes to the file, or if a new config file is created.
- Adds an **Aspire** node to Services: **AppHost → resources**.
- **AppHost node:** Start / Stop / Restart, Open Dashboard, *Restore TypeScript SDK* (`aspire restore`),
  open `aspire.config.json`, Refresh. Its console shows the CLI output plus the logs of every resource, each line tagged with the resource name.
  Double-click opens the AppHost file.
- **Resource nodes:** live state, health and URL, plus a console with that resource's logs.
  Start / Stop / Restart are enabled from the commands the resource itself offers. There are also Open URL and Open in Dashboard,
  and any custom commands the resource defines.
- It attaches to an AppHost that is already running, including one started from a terminal with `aspire run` or `aspire start`.

## How it works

The plugin has no Aspire-specific protocol code. Instead, it uses the `aspire` CLI directly and provides a structured service from the outputs:

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

To build and run this plugin locally, please note the following.

```bash
./gradlew buildPlugin          # -> build/distributions/aspire-apphost-services-*.zip
./gradlew runIde -PopenProject=/path/to/repo-with-aspire.config.json
./gradlew runGoland -PopenProject=/path/to/repo   # same, in GoLand (golandLocalPath)
./gradlew verifyPlugin  # This plugin targets IntelliJ IDEA, WebStorm and GoLand, 2024.3 → Current Release
```


## Source Description


- `cli/`: CLI wrapper, JSON models, `aspire.config.json` parsing
- `services/AspireAppHostService.kt`: state, CLI streams, log routing
- `services/AspireServiceViewContributor.kt`: Services tree
- `services/LogSink.kt`: buffered consoles
- `actions/`: toolbar and popup actions
- `settings/`: CLI path setting