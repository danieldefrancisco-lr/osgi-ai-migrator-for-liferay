---
name: osgi-ai-migrator
description: Migrate a legacy Liferay 7.x OSGi module (Service Builder entities, JSP portlets, model listeners, service wrappers, custom LocalServiceImpl logic, e-mail notifications, theme WARs) into Liferay Objects and Client Extensions in a Liferay workspace, with formats validated on Liferay DXP 2026. Use when the user asks to migrate, modernize or port an OSGi module, portlet, Service Builder service or theme to Objects or Client Extensions.
---

# OSGi AI Migrator for Liferay

You migrate one legacy OSGi module at a time into a Liferay workspace. The deterministic parts come from the bundled
CLI (`scripts/osgi-ai-migrator-cli.jar`, Java 17 or later): it analyzes the module and checks the files you write.
You do the architecture and the code, following the playbooks in `references/knowledge/`, which hold templates
validated on a real Liferay. Copy those templates instead of improvising formats: most deployment failures come
from formats that look right but that Liferay rejects.

Paths below are relative to this skill's folder. `<cli>` means `java -jar scripts/osgi-ai-migrator-cli.jar`.

## 1. Analyze

```
<cli> analyze <module-dir> --json
```

Read `report`, `entities`, `portlets`, `customLogic` and `fieldRenames`, then read the module's source files yourself
(the analysis lists them; read them in full, the CLI does not truncate anything you open directly). Find the Liferay
workspace: the folder with `settings.gradle` / `gradle.properties` and `client-extensions/` (ask the user if the
module is not inside one).

## 2. Plan

Read `references/knowledge/playbooks/02-mapping-rules.md` and, when they apply, the blueprints in
`references/knowledge/blueprints/` (data migration, notifications, themes). Write the plan to
`docs/migrations/<module>-plan.md` in the workspace and show it to the user before generating. It must state:

- each Object: name, `externalReferenceCode`, endpoint `/o/c/<lowercase plural>`, fields with DBType and business type;
  apply every rename in `fieldRenames` (reserved names such as `status` become `<entity>Status`);
- a decision for **every** line of `customLogic`: logging only → entry history; fixed field values, e-mail or a plain
  HTTP POST → native Object Action; calculations, conditions on previous values, external calls with custom payloads
  or several steps → the module's single Spring Boot microservice; `query` methods → REST filters; validations that
  throw → Object Validations (created by hand, listed in `MIGRATION.md`). When one method mixes fixed values and
  computed ones, keep it whole in the microservice (one action per trigger), so updates do not race;
- the legacy `ModelListenerException` rollbacks you cannot keep (actions run after the save), noted for `MIGRATION.md`;
- data: plan an export of the legacy tables (data migration blueprint) only if the user asks for it; never invent entries;
- each Object Action (name in camelCase, `externalReferenceCode`, trigger, executor) and Notification Template;
- the client extensions to create and, for the microservice, its id and port (58081).

## 3. Generate

Read `references/knowledge/playbooks/03-generation-rules.md` and copy its templates: batch files (section 2), React
custom elements (3), Object Actions, notification templates and the Spring Boot microservice (4), themes (8 and 9).
The rules Liferay enforces, all found in real deployments:

- Every object field and every action has its own fixed `externalReferenceCode` (naming convention in playbook
  section 2.1: `OBJ_LOAN`, `FIELD_LOAN_DUEDATE`, `ACTION_LOAN_RETURNLOAN`), or redeploying fails with `Duplicate name`.
  Batch files: `01-00` folder, `01-01` picklists (if any), `01-02` definition. Actions live in the `objectActions` array of the object definition, never in their own file.
- `update-object-entry` needs `objectDefinitionExternalReferenceCode` and only fixed values; a `standalone` action needs
  `errorMessage`; triggers are `onAfterAdd`, `onAfterUpdate`, `onAfterDelete`, `standalone` (no `onBefore*`);
  `conditionExpression` cannot see previous values.
- Notification terms are `[%<OBJECTNAME>_<FIELDNAME>%]` from the object's `name`, not its ERC, and only its own fields.
  Order: object definition → template → the complete definition again with the notification action.
- Never write entries (`ObjectEntry`) files: you cannot see the legacy data.
- The microservice: one per module, `Dockerfile` and `LCP.json` included, `.serviceAddress: localhost:58081`, OAuth
  scope `c_<objectname>.everything`, calls back to Liferay only with Spring's `RestClient` and the JWT (there is no
  Liferay Java client for objects), dates read from `objectEntryDTO<Object>.properties` and written without fractions
  of a second.

Write `MIGRATION.md` in each client extension with the manual steps (validations, mail server, permissions) and,
for a microservice, the environment table of playbook section 4.4.7: how to set `.serviceAddress` for a local
bundle, Docker (`host.docker.internal`), Docker Compose, a remote server or SaaS.

## 4. Verify

```
<cli> verify <workspace-dir> --only <client-extension>,<client-extension>
```

Exit code 0 means clean; 3 means warnings. Fix every warning and run it again until it is clean. The checks encode
the failures above, so do not dismiss a warning without telling the user why.

## 5. Build and deploy (when the user wants it)

From the workspace root: `blade gw :client-extensions:<name>:build` (or `./gradlew`), then copy the zip from `dist/`
(or the client extension's `dist/`) to the Liferay `deploy/` folder. Order: microservice first (it registers the
`objectAction`), start it with `blade gw :client-extensions:<name>:bootRun`, then the batch, then the frontend.
Read the Liferay log after each deployment (`docker logs <container>` or `logs/liferay.*.log`): the batch engine
reports each file as `Successfully deployed` or with the exception to fix. If the user tests the actions, the
microservice logs the JSON Liferay sends; compare it with playbook section 4.4.6.

## Notes

- `<cli> knowledge <dir>` exports the same playbooks, if you need them elsewhere.
- Do not migrate several modules at once: finish, verify and deploy one before the next.
- Never put credentials in the generated files.
