# Modernization Report & Action Guide

- **Generated on:** <TIMESTAMP>
- **Source Module:** `acme-theme`
- **Source Liferay Platform Version:** `Liferay BOM 7.4.13.u100`
- **Target Standard:** Liferay DXP 7.4+ Client Extensions & Objects

---

## 1. Donatello (The Analyzer) - Legacy Extraction Summary

```
================================================================================
DONATELLO (THE ANALYZER) - LEGACY MODULE EXTRACTION REPORT
================================================================================

Module Name: acme-theme
Source Liferay Platform Version: Liferay BOM 7.4.13.u100
Liferay Workspace Root: ${ROOT}/maven-workspace

1. DOMAIN & SERVICE BUILDER:
   - Has service.xml: NO
   - Entities Detected (0): 

2. USER INTERFACE & PORTLETS:
   - Has JSP / Portlet: NO
   - Portlets Detected (0): 

3. OSGI EVENT LISTENERS & LOGIC:
   - Model Listeners Detected (0): 
   - Schedulers Detected (0): 
   - Email/Notifications Detected (0): 
   - Custom Logic to Port (0):

4. THEMES & LOOK AND FEEL:
   - Has Theme Artifacts: YES
       * Theme Acme Theme (acme)
       * 11 SCSS sources, 6 of the theme's own: css/_clay_variables.scss, css/_custom.scss, css/acme/_header.scss, css/acme/_index.scss, css/acme/_sticky.scss, css/acme/_variables.scss
       * 1 Style Book tokens in WEB-INF/frontend-token-definition.json
       * 2 images and fonts of the theme, custom favicon (+ 0 base theme images, copied only if its SCSS uses them)
       * JavaScript with code: js/sticky.js
       * Templates: templates/navigation.ftl, templates/portal_normal.ftl
       * Configurable settings: show-header-search, enable-sticky-header
       * Not migrated: images/clay/icons.svg: the Clay icon set of the base theme (registering it as a spritemap would replace the current icons)
       * Not migrated: js/main.js: the empty main.js of the theme generator

5. EXTRACTED SOURCE FILES (3 files):
   - theme:liferay-look-and-feel.xml
   - theme:navigation.ftl
   - theme:portal_normal.ftl
```

---

## 2. Leonardo (The Architect) - Modernization Blueprint

## Modernization Blueprint: acme-theme

### 1. Theme client extension
- Written by the migrator: `client-extensions/acme-theme` (theme CSS, 1 Style Book token, logo, favicon, sticky.js).

### 2. Fragments (collection `acme-theme-fragments`)
- `acme-theme-header`: the `<header class="acme-header" id="banner">` of portal_normal.ftl.
  - Editable logo image and link, editable site name.
  - Search area shown when the configuration field `showHeaderSearch` (from the setting `show-header-search`) is on, with a drop zone for the Search Bar widget.
  - Drop zone for the Menu Display widget (replaces navigation.ftl).
  - `enableStickyHeader` (setting `enable-sticky-header`) toggles the `acme-sticky` class on scroll.
- `acme-theme-footer`: the `<footer class="acme-footer">` with an editable text.

### 3. Master page
- Header fragment, page drop zone, footer fragment; theme CSS `Acme Theme CSS`.

### API CONTRACT
- No Liferay Objects.


---

## 3. Michelangelo (The Coder) - Generated Artifacts Inventory

- `fragments/acme-theme-fragments/acme-theme-header/index.js`
- `client-extensions/acme-theme/src/css/_custom.scss`
- `fragments/acme-theme-fragments/collection.json`
- `fragments/acme-theme-fragments/acme-theme-header/configuration.json`
- `fragments/acme-theme-fragments/acme-theme-footer/fragment.json`
- `fragments/acme-theme-fragments/acme-theme-footer/index.css`
- `client-extensions/acme-theme/package.json`
- `fragments/acme-theme-fragments/acme-theme-header/fragment.json`
- `fragments/acme-theme-fragments/acme-theme-footer/index.html`
- `fragments/acme-theme-fragments/acme-theme-header/index.html`
- `fragments/acme-theme-fragments/acme-theme-header/index.css`
- `client-extensions/acme-theme/client-extension.yaml`
- 13 files copied as they are from the legacy module (SCSS, images, fonts, JavaScript)

---

## Theme Acme Theme

The client extension `client-extensions/acme-theme` was built without AI from the theme's files:

- Theme Acme Theme (acme)
- 11 SCSS sources, 6 of the theme's own: css/_clay_variables.scss, css/_custom.scss, css/acme/_header.scss, css/acme/_index.scss, css/acme/_sticky.scss, css/acme/_variables.scss
- 1 Style Book tokens in WEB-INF/frontend-token-definition.json
- 2 images and fonts of the theme, custom favicon (+ 0 base theme images, copied only if its SCSS uses them)
- JavaScript with code: js/sticky.js
- Templates: templates/navigation.ftl, templates/portal_normal.ftl
- Configurable settings: show-header-search, enable-sticky-header
- Not migrated: images/clay/icons.svg: the Clay icon set of the base theme (registering it as a spritemap would replace the current icons)
- Not migrated: js/main.js: the empty main.js of the theme generator

### Deploy and apply

1. `blade gw :client-extensions:acme-theme:build` and copy the zip from `dist/` to Liferay's `deploy/` folder.
   The workspace compiles `src/css` on top of the "styled" design pack: if a SCSS import fails, the theme used a
   partial of its 7.x base theme that the design pack does not have; copy it from the legacy theme into `src/css`.
2. Try it on a test page first: **Configure → Design → Theme CSS** → *Acme Theme CSS*.
3. Its 1 tokens appear in **Design → Style Books** for that theme CSS: create a Style Book to change them.
4. Add the JavaScript client extensions (js/sticky.js) in **Configure → Design → JavaScript**;
   review them first: AUI and theme-template variables are not available outside a classic theme.

### Still to do by hand (or with the generated fragments)

- The FreeMarker templates (templates/navigation.ftl, templates/portal_normal.ftl) become a master page with header and
  footer fragments; the theme CSS keeps styling them through the same CSS classes.
- Theme settings that become fragment configuration or master page choices:
  - `show-header-search` (checkbox, default `true`)
  - `enable-sticky-header` (checkbox, default `false`)
- Not migrated: images/clay/icons.svg: the Clay icon set of the base theme (registering it as a spritemap would replace the current icons)
- Not migrated: js/main.js: the empty main.js of the theme generator

---

## 4. Raphael (The Verifier) - Post-Migration Action Checklist

================================================================================
RAPHAEL (THE VERIFIER) - PRE-FLIGHT QUALITY ASSURANCE REPORT
================================================================================

PRE-FLIGHT CHECKS:
   [PASS] Canonical client-extension.yaml present
   [WARN] client-extensions/acme-theme/src/css/_custom.scss styles body/html at the top level, which also restyles Liferay's menus; scope the rules under #wrapper.

POST-MIGRATION ACTION CHECKLIST & MANUAL STEPS:

1. DEPLOY THE THEME CLIENT EXTENSION:
     blade gw :client-extensions:acme-theme:deploy

2. IMPORT THE FRAGMENTS (IF GENERATED): Site Administration -> Design -> Fragments -> (...) -> Import ->
   fragments/acme-theme-fragments.zip

3. BUILD A MASTER PAGE: Design -> Masters -> Add; header fragment above and footer fragment below the page area;
   fill their drop zones with the widgets named in the fragments' MIGRATION.md; in its Look and Feel select
   'Acme Theme CSS'; publish and use it for a test page.

4. COMPARE the test page with the original theme, then apply the master page to the site's pages.

