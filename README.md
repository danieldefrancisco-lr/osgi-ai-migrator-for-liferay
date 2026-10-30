# OSGi AI Migrator for Liferay

Eclipse plugin that uses Google Gemini to help modernize legacy Liferay 7.x OSGi modules
into Liferay Objects and Client Extensions.

> **Disclaimer:** this is an independent personal project. It is not affiliated with,
> endorsed by, or supported by Liferay, Inc. Liferay and Liferay DXP are trademarks of
> Liferay, Inc.

## Features

- **Context menu**: right-click a module → **OSGi AI Migrator → Migrate OSGi Module to Client Extensions...**
- **4-agent migration wizard**: Donatello (analyze), Leonardo (architect), Michelangelo (generate), Raphael (verify).
- **Analyzer** for `service.xml`, JSPs, portlet classes, model listeners, schedulers and theme templates.
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

## Build

Requires JDK 17+. Maven is downloaded by the wrapper (pinned to 3.9.11).

```
./mvnw clean verify      # Windows: mvnw.cmd clean verify
```

The p2 update site is generated in
`releng/io.github.danieldefrancisco_lr.migrator.updatesite/target/repository/`
(also zipped next to it).

## Releasing

Pushes to `main` and pull requests only build. The update site is published to GitHub Pages
when a `v*` tag is pushed, and the tag must match the plugin and feature versions:

1. Set the new version in `pom.xml` files, `MANIFEST.MF` and `feature.xml`, for example:
   `./mvnw org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion=2.1.0-SNAPSHOT`
2. Commit and push to `main`, and check the build passes.
3. Tag and push: `git tag v2.1.0 && git push origin v2.1.0`

## Project layout

```
pom.xml                                               Maven/Tycho parent
bundles/io.github.danieldefrancisco_lr.migrator       The plugin
features/io.github.danieldefrancisco_lr.migrator.feature
releng/io.github.danieldefrancisco_lr.migrator.target      Target platform (Eclipse 2024-12)
releng/io.github.danieldefrancisco_lr.migrator.updatesite  p2 update site (category.xml)
```

## License

Copyright (c) 2026 Daniel de Francisco.

Licensed under the [Apache License, Version 2.0](LICENSE).
