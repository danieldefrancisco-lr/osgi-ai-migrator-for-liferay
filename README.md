# OSGi AI Migrator for Liferay

Eclipse plugin that uses Google Gemini to help modernize legacy Liferay 7.x OSGi modules
into Liferay Objects and Client Extensions.

> **Disclaimer:** this is an independent personal project. It is not affiliated with,
> endorsed by, or supported by Liferay, Inc. Liferay and Liferay DXP are trademarks of
> Liferay, Inc.

## Features

- **Context menu**: right-click a module → **OSGi AI Migrator → Migrate OSGi Module to Client Extensions...**
- **4-agent migration wizard**: Donatello (analyze), Leonardo (architect), Michelangelo (generate), Raphael (verify).
- **Analyzer** for `service.xml`, JSPs, portlet classes, model listeners, schedulers and theme templates. It lists every
  method of Model Listeners, Service Wrappers and custom `*LocalServiceImpl` classes with the Object Action trigger it maps to.
- **Object Actions**: native actions (notification, webhook, update entry) and notification templates in the Batch
  Client Extension, and a Spring Boot `objectAction` client extension for custom logic, with the setup for each way
  Liferay runs (local bundle, Docker, remote server, SaaS/PaaS) in `MIGRATION.md`.
- **Raphael's checks** for the mistakes Liferay rejects at deployment (reserved field names, missing external
  reference codes, invalid action parameters and notification terms, microservice ports and OAuth scopes...).
- **Agent skill** for Claude Code, Gemini CLI, Codex and other coding agents, built from the same playbooks
  ([Agent skill](#agent-skill-claude-code-gemini-cli-codex-and-other-coding-agents)).
- **Gemini authentication**: gcloud ADC, Vertex AI service account, or Google AI Studio API key
  (stored in Eclipse Secure Storage).

## Requirements

- Eclipse 2024-12 (4.34) or later, or Liferay Developer Studio
- Java 17 or later
- Access to Gemini through Google AI Studio or Vertex AI

## Install

From the [Eclipse Marketplace](https://marketplace.eclipse.org/content/osgi-ai-migrator-liferay):
drag this button into a running Eclipse workspace.

<a href="https://marketplace.eclipse.org/marketplace-client-intro?mpc_install=7577081" title="Drag to your running Eclipse workspace to install OSGi AI Migrator for Liferay"><img src="https://marketplace.eclipse.org/modules/custom/eclipsefdn/eclipsefdn_marketplace/images/btn-install.svg" alt="Drag to your running Eclipse workspace to install OSGi AI Migrator for Liferay" /></a>

Or use *Help → Install New Software → Add…* and enter the update site URL:

```
https://danieldefrancisco-lr.github.io/osgi-ai-migrator-for-liferay/
```

## IntelliJ IDEA and other JetBrains IDEs

The `intellij/` plugin works in every JetBrains IDE from 2024.3 on. Right-click a module (or one of its
folders) in the Project view and choose **OSGi AI Migrator → Migrate OSGi Module to Client Extensions...**;
Gemini is configured in **Settings → Tools → OSGi AI Migrator for Liferay** (the API key goes to the IDE's
password safe). Until it is on the JetBrains Marketplace, install the zip with
**Settings → Plugins → ⚙ → Install Plugin from Disk...**.

To build it, install the core with Maven first, then use Gradle:

```
./mvnw install
cd intellij && ./gradlew buildPlugin      # zip in intellij/build/distributions/
```

`./gradlew runIde` starts a sandbox IDE with the plugin for testing.

## Visual Studio Code

The `vscode/` extension runs the bundled CLI, so it needs Java 17 or later (setting
**OSGi AI Migrator: Java Home** if `java` on the PATH is older). Right-click a module folder in the Explorer and
choose **Migrate OSGi Module to Client Extensions...**. Gemini settings are under **OSGi AI Migrator** in the
VS Code settings; for AI Studio, run **OSGi AI Migrator: Set Gemini API Key** (kept in VS Code secret storage).
Install it from the
[VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=DanieldeFrancisco.osgi-ai-migrator)
(search for "OSGi AI Migrator" in the Extensions view, or run `code --install-extension DanieldeFrancisco.osgi-ai-migrator`).
Each GitHub Release also has the `.vsix`, for **Extensions → ... → Install from VSIX...**.

To build it, run `./mvnw install` first (it builds the CLI jar), then:

```
cd vscode && npm install && npm run package   # .vsix in vscode/dist/
```

## Command line

The same migration runs from a terminal with the CLI, a single jar that needs Java 17 or later
(`osgi-ai-migrator-cli.jar`, built in `cli/target/`):

```
java -jar osgi-ai-migrator-cli.jar analyze path/to/legacy-module
java -jar osgi-ai-migrator-cli.jar migrate path/to/legacy-module                # review the plan and Raphael's checks
java -jar osgi-ai-migrator-cli.jar migrate path/to/legacy-module --apply        # write into the Liferay workspace
java -jar osgi-ai-migrator-cli.jar migrate path/to/legacy-module --save-plan plan.txt
java -jar osgi-ai-migrator-cli.jar apply path/to/legacy-module --plan plan.txt  # write a reviewed plan
```

Two commands work without Gemini, for client extensions written or edited by hand or by a coding agent:

```
java -jar osgi-ai-migrator-cli.jar verify path/to/liferay-workspace         # Raphael's checks; exit code 3 if there are warnings
java -jar osgi-ai-migrator-cli.jar verify path/to/liferay-workspace --only loan-object,loan-object-action
java -jar osgi-ai-migrator-cli.jar knowledge path/to/dir                   # export this version's playbooks and blueprints
```

`verify` reads every client extension and fragment of the workspace (skipping `build/`, `dist/` and `node_modules/`),
so cross-file checks such as `function#` actions without their microservice still work with `--only`.

Other options: `--folder <path>` (one folder of the module), `--no-backend` (frontend only),
`--workspace <dir>`, `--json` (machine-readable output). Run with `--help` for all of them.

Gemini settings are resolved from the command line (`--auth`, `--model`, `--project`, `--region`,
`--service-account`, `--temperature`, `--endpoint`), then `~/.osgi-ai-migrator/config.properties`
(on Windows `%USERPROFILE%\.osgi-ai-migrator\config.properties`), then the standard Google environment
variables (`GEMINI_API_KEY`, `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_CLOUD_PROJECT`,
`GOOGLE_CLOUD_LOCATION`). The file wins over the environment because those variables are often set for
other tools. It uses the same keys as the Eclipse preferences:

```
geminiAuthType=GCLOUD_ADC
vertexProjectId=my-gcp-project
vertexRegion=us-central1
geminiModel=gemini-2.5-flash
```

As in Eclipse, the default authentication is gcloud ADC (`gcloud auth login`); use
`geminiAuthType=AI_STUDIO` (or `--auth AI_STUDIO`) for an API key. `java -jar osgi-ai-migrator-cli.jar config`
shows the settings in effect and where each one comes from.

## Agent skill (Claude Code, Gemini CLI, Codex and other coding agents)

The migration is also packaged as an [Agent Skill](skill/src/SKILL.md), so a coding agent can do it itself instead of
Gemini inside the IDE. The agent analyzes the module with the bundled CLI, writes a plan, generates the client
extensions from the same playbooks the plugins use, and runs `verify` until it is clean; on request it also builds
and deploys them and reads Liferay's log to fix what fails. Because it reads the whole module and checks its own
work, it usually gets further than the one-shot Gemini pipeline.

### Requirements

- Java 17 or later on the machine where the agent runs (`java -version`). If `java` on the `PATH` is older, tell the
  agent the full path of a newer one (e.g. `C:\Program Files\Java\jdk-21\bin\java.exe`).
- The legacy module and a Liferay workspace (the folder with `settings.gradle` and `client-extensions/`). To build and
  deploy, also Blade or the workspace's Gradle wrapper, and a Liferay to deploy to.
- An agent that can read files and run shell commands. Chat apps without access to your disk (e.g. claude.ai) cannot
  use it.

### Get the skill

Download `osgi-ai-migrator-skill-X.zip` from the [GitHub Releases](https://github.com/danieldefrancisco-lr/osgi-ai-migrator-for-liferay/releases)
(from 3.4.0 on), or build it: `./mvnw install` writes `skill/target/osgi-ai-migrator-skill.zip`. The zip holds one
folder, `osgi-ai-migrator/`:

```
osgi-ai-migrator/
├── SKILL.md                  instructions (agent-neutral)
├── AGENTS.md, GEMINI.md      short pointers to SKILL.md
├── references/knowledge/     playbooks, blueprints and schemas of this version
└── scripts/osgi-ai-migrator-cli.jar
```

### Install in Claude Code

Unzip it into your personal skills folder, so the skill is available in every project:

```bash
mkdir -p ~/.claude/skills && unzip osgi-ai-migrator-skill-X.zip -d ~/.claude/skills
```

On Windows (PowerShell):

```powershell
Expand-Archive osgi-ai-migrator-skill-X.zip -DestinationPath "$env:USERPROFILE\.claude\skills"
```

To share it with a team through a repository instead, unzip it into the Liferay workspace's `.claude/skills/` and
commit it. Start a new Claude Code session afterwards: skills are loaded when a session starts. The folder must end
up as `.../skills/osgi-ai-migrator/SKILL.md`.

### Install in Gemini CLI, Codex and other agents

These agents read project instructions from `GEMINI.md` or `AGENTS.md`. Unzip the skill somewhere stable (for
example `~/agent-skills/osgi-ai-migrator/`), then add this to the `GEMINI.md` or `AGENTS.md` at the root of your
Liferay workspace (create it if needed), with your path:

```markdown
## Migrating legacy Liferay OSGi modules
To migrate an OSGi module to Liferay Objects and Client Extensions, read and follow
~/agent-skills/osgi-ai-migrator/SKILL.md. Paths in it (scripts/, references/) are relative to that folder.
```

If your agent supports Agent Skills folders natively, you can unzip it there instead, as for Claude Code.

### Use it

Open the agent in the Liferay workspace and ask for the migration, naming the module:

```
Migrate modules/loans/loan-service to Liferay Objects and Client Extensions.
```

In Claude Code the skill is picked up from that request, or you can call it explicitly with `/osgi-ai-migrator`.
The agent then:

1. Runs `analyze` on the module and reads its sources.
2. Writes the plan to `docs/migrations/<module>-plan.md` (objects, field renames, a decision for every model
   listener and service method, actions, microservice) and asks you to approve it. Review it before saying yes.
3. Generates the client extensions under `client-extensions/`, with a `MIGRATION.md` in each one.
4. Runs `verify` until it reports no problems.
5. If you ask, builds and deploys them (microservice first, then the batch, then the frontend) and checks the log.

Things to tell it when they apply: how your Liferay runs (local bundle, Docker, remote, SaaS), so the microservice
address in `client-extension.yaml` is set for it (see the table in each `MIGRATION.md`); where the `deploy/` folder
is; and the path of Java 17+ if the default `java` is older. Migrate one module per conversation.

You can also run the same checks on any workspace yourself: `java -jar scripts/osgi-ai-migrator-cli.jar verify
<workspace> --only <client-extension>`.

### Update or remove

Replace the `osgi-ai-migrator/` folder with the one from a newer zip (the skill, its playbooks and its CLI always
come together), or delete it to uninstall. For Gemini CLI and other agents, also remove the lines you added to
`GEMINI.md` / `AGENTS.md`.

### How it is built

`skill/` only holds the instructions (`SKILL.md`, `AGENTS.md`, `GEMINI.md`). The build copies the knowledge base from
the core jar and the CLI jar into the zip, so the skill and the IDE plugins always share the same version and rules,
and every tagged release attaches the zip.

## Build

Requires JDK 17+. Maven is downloaded by the wrapper (pinned to 3.9.11).

```
./mvnw clean verify      # Windows: mvnw.cmd clean verify
```

The p2 update site is generated in
`releng/io.github.danieldefrancisco_lr.migrator.updatesite/target/repository/`
(also zipped next to it).

## Tests

The build runs characterization tests that pin the plugin's behavior. They run the whole pipeline on the
synthetic legacy modules in `testdata/fixtures`, with Gemini replaced by the canned answers in
`testdata/responses`, and compare everything the plugin produces (analysis, the exact prompts sent to each
agent, reports and the files written to the workspace) with `testdata/golden`:

- `core` runs them outside Eclipse, the way the CLI and other IDEs will.
- `bundles/io.github.danieldefrancisco_lr.migrator.tests` runs them inside Eclipse, through the plugin's
  workspace integration and the "Apply to Workspace" step.

When a change is meant to alter the output (for example, editing a playbook changes the prompts), update the
goldens and review the diff before committing:

```
./mvnw clean verify -Dgolden.update=true
git diff testdata/golden
```

On Linux the Eclipse tests need a display; CI runs them with `xvfb-run`.

## Releasing

All products share one version, taken from the Maven poms (the IntelliJ plugin and the VS Code
extension read it from there in CI). Pushes to `main` and pull requests only build. Pushing a `v*` tag that
matches that version publishes the Eclipse update site to GitHub Pages and creates a GitHub Release with the
update site zip, the CLI jar, the IntelliJ plugin zip and the VS Code `.vsix`. It also publishes to the
JetBrains Marketplace, the VS Code Marketplace and Open VSX when the repository secrets `JETBRAINS_TOKEN`,
`VSCE_PAT` and `OVSX_PAT` are set (each store is skipped otherwise):

1. Set the new version, for example
   `./mvnw org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion=3.4.0-SNAPSHOT`
   (and `npm version 3.4.0 --no-git-tag-version` in `vscode/` to keep local builds in step).
2. Commit and push to `main`, and check the build passes.
3. Tag and push: `git tag v3.4.0 && git push origin v3.4.0`

## Project layout

```
pom.xml                                               Maven/Tycho parent
core                                                  Migration engine, plain Java (no Eclipse dependencies):
                                                      analyzer, agents, Gemini client, reports, file writer,
                                                      knowledge/ playbooks
bundles/io.github.danieldefrancisco_lr.migrator       Eclipse plugin: menu, wizard, preferences;
                                                      embeds core as lib/*.jar (copied by the build)
bundles/io.github.danieldefrancisco_lr.migrator.tests Eclipse characterization tests (not shipped)
features/io.github.danieldefrancisco_lr.migrator.feature
releng/io.github.danieldefrancisco_lr.migrator.target      Target platform (Eclipse 2024-12)
releng/io.github.danieldefrancisco_lr.migrator.updatesite  p2 update site (category.xml)
cli                                                   Command-line interface (runnable jar)
intellij                                              IntelliJ Platform plugin (Gradle)
vscode                                                VS Code extension (TypeScript), runs the CLI
skill                                                 Agent skill: instructions; the build adds the playbooks and the CLI
testdata                                              Test fixtures, canned Gemini answers and goldens
```

To work on the plugin in Eclipse (PDE), run the Maven build once so `lib/` contains the core jar.

## License

Copyright (c) 2026 Daniel de Francisco.

Licensed under the [Apache License, Version 2.0](LICENSE).
