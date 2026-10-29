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

*Help → Install New Software → Add…* and enter the update site URL:

```
https://danieldefrancisco-lr.github.io/osgi-ai-migrator-for-liferay/
```

## Build

Requires Maven 3.9+ and JDK 17+.

```
mvn clean verify
```

The p2 update site is generated in
`releng/io.github.danieldefrancisco_lr.migrator.updatesite/target/repository/`
(also zipped next to it).

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
