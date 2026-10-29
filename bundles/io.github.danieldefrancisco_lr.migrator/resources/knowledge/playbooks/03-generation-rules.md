# Michelangelo (The Coder) Playbook: Automated Code Generation Rules

## 1. Mission & Scope
The Coder agent (Michelangelo) takes blueprints from Leonardo and generates production-ready, deployable source code into `/client-extensions/`:
1. **Batch Client Extension**: Initializes Liferay Object folders, object definitions, and relationships upon deployment.
2. **Custom Element Client Extension**: Standalone React/Clay UI component that replaces legacy JSP portlets.

---

## 2. Batch Client Extension Generation (Liferay Objects)

### 2.1 File & Directory Layout
```
client-extensions/[entity-slug]-object/
├── client-extension.yaml
└── batch/
    ├── 01-00-folder-definition.batch-engine-data.json
    └── 01-01-[entity-name]-definition.batch-engine-data.json
```
*Note: Alphabetic ordering (`01-00-`, `01-01-`, `02-00-`) is required because the Liferay Batch Engine processes files alphabetically.*

### 2.2 `client-extension.yaml` Specification
```yaml
assemble:
  - from: batch
    into: batch

[entity-slug]-object-batch:
  name: "[Entity Name] Object Initialization"
  type: batch
  oAuthApplicationHeadlessServer: [entity-slug]-oauth-server

[entity-slug]-oauth-server:
  name: "[Entity Name] OAuth Server"
  type: oAuthApplicationHeadlessServer
  .serviceAddress: localhost:8080
  .serviceScheme: http
  scopes:
    - Liferay.Headless.Batch.Engine.everything
    - Liferay.Object.Admin.REST.everything
```
*Pitfall Prevention:*
- Do not omit `.serviceAddress` or `.serviceScheme` — doing so causes NPEs during bundle initialization.
- Include both OAuth scopes (`Headless.Batch.Engine` AND `Object.Admin.REST`).

### 2.3 Folder Definition (`01-00-folder-definition.batch-engine-data.json`)
```json
{
  "configuration": {
    "className": "com.liferay.object.admin.rest.dto.v1_0.ObjectFolder",
    "parameters": {
      "createStrategy": "UPSERT",
      "updateStrategy": "UPDATE"
    }
  },
  "items": [
    {
      "name": "MigrationObjects",
      "label": {
        "en_US": "Migrated Objects"
      },
      "externalReferenceCode": "FOLDER_MIGRATED_OBJECTS"
    }
  ]
}
```

### 2.4 Object Definition (`01-01-[entity-slug]-definition.batch-engine-data.json`)
```json
{
  "configuration": {
    "className": "com.liferay.object.admin.rest.dto.v1_0.ObjectDefinition",
    "parameters": {
      "createStrategy": "UPSERT",
      "updateStrategy": "UPDATE"
    }
  },
  "items": [
    {
      "accountEntryRestricted": false,
      "active": true,
      "defaultLanguageId": "en_US",
      "enableCategorization": true,
      "enableComments": true,
      "enableIndexSearch": true,
      "enableLocalization": false,
      "enableObjectEntryDraft": false,
      "enableObjectEntryHistory": false,
      "externalReferenceCode": "OBJ_[ENTITY_NAME_UPPER]",
      "label": {
        "en_US": "[Entity Name]"
      },
      "name": "[EntityName]",
      "objectFolderExternalReferenceCode": "FOLDER_MIGRATED_OBJECTS",
      "panelCategoryKey": "control_panel.object",
      "pluralLabel": {
        "en_US": "[Entity Plural]"
      },
      "portlet": true,
      "scope": "company",
      "status": {
        "code": 0,
        "label": "approved"
      },
      "system": false,
      "titleObjectFieldName": "[titleFieldName]",
      "objectFields": [
        {
          "DBType": "String",
          "businessType": "Text",
          "externalReferenceCode": "FIELD_[ENTITY]_[FIELDNAME_UPPER]",
          "indexed": true,
          "indexedAsKeyword": true,
          "label": {
            "en_US": "Field Label"
          },
          "name": "fieldName",
          "required": true,
          "system": false,
          "type": "String",
          "unique": false
        },
        {
          "DBType": "Date",
          "businessType": "Date",
          "externalReferenceCode": "FIELD_[ENTITY]_DATE_UPPER",
          "indexed": true,
          "indexedAsKeyword": false,
          "label": {
            "en_US": "Record Date"
          },
          "name": "recordDate",
          "objectFieldSettings": [
            {
              "name": "timeStorage",
              "value": "convertToUTC"
            }
          ],
          "required": false,
          "system": false,
          "type": "Date",
          "unique": false
        }
      ]
    }
  ]
}
```

### 2.5 Strict Rules for Batch JSON Generation
1. **Status Code**: `"status": {"code": 0, "label": "approved"}` is mandatory. Without this, the object is created in `draft` status and cannot receive entries or be used in UI without manual admin approval.
2. **Date vs DateTime Fields**:
   - **`DateTime` (with time/timestamp)**: MUST include `"objectFieldSettings": [{"name": "timeStorage", "value": "convertToUTC"}]`.
   - **`Date` (pure date without time)**: `"objectFieldSettings": []`. **NEVER** add `timeStorage` to a `Date` field (throws `ObjectFieldSettingNameException: The settings timeStorage are not allowed for object field [name]`).
3. **No `objectValidations` inside `ObjectDefinition`**: `ObjectDefinition` does NOT have an `objectValidations` field (throws `NoSuchFieldException: [objectValidations]`). Validations are handled via separate `ObjectValidationRule` DTOs or configured in Liferay UI.
4. **No `localized` property inside `ObjectField`**: Localization is an object-level toggle (`"enableLocalization": true/false`). Individual fields must NOT contain `"localized": true/false`!
5. **No `indexedLanguageId` on Non-Strings**: Only `String` and `Clob` fields may have `indexedLanguageId`.
6. **No `permissions` block**: Permissions cannot be set inside the batch object definition schema.
7. **No System Audit Fields**: Do not include `companyId`, `groupId`, `userId`, `createDate`, `modifiedDate`, etc. Liferay creates them automatically!

---

## 3. Custom Element Client Extension Generation (React JavaScript UI)

When replacing legacy JSP portlets, Michelangelo generates a **React Custom Element Client Extension** using standard **JavaScript** and the canonical **`react-scripts`** build tool (Create React App standard), matching verified Liferay references (`references/bcm-lines-scheduler` and `references/dmb-antragsstrecke-object-custom-element`):

### 3.1 File & Directory Layout
```
client-extensions/[portlet-slug]-custom-element/
├── client-extension.yaml
├── package.json
├── public/
│   └── index.html
└── src/
    ├── index.js
    ├── App.js
    ├── index.css
    ├── components/
    │   ├── Form.js
    │   └── ListView.js
    └── services/
        └── liferayApi.js
```
*Strict Language Rule:* ALWAYS use plain **JavaScript** (`.js`, `.jsx`). **NEVER** use TypeScript (`.ts`, `.tsx`) unless explicitly requested!

### 3.2 `package.json` Specification
Use standard `react-scripts` build and start scripts with zero brittle external sub-dependencies:
```json
{
  "name": "[portlet-slug]-custom-element",
  "version": "0.1.0",
  "private": true,
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0",
    "react-scripts": "5.0.1"
  },
  "scripts": {
    "start": "react-scripts start",
    "build": "react-scripts build"
  },
  "browserslist": {
    "production": [
      ">0.2%",
      "not dead",
      "not op_mini all"
    ],
    "development": [
      "last 1 chrome version",
      "last 1 firefox version",
      "last 1 safari version"
    ]
  }
}
```

### 3.3 `client-extension.yaml` Specification
- **Assemble:** Pulls from `build/static` (standard `react-scripts` output) into `static`.
- **URLs:** Uses glob patterns `js/main.*.js` and `css/main.*.css` because `react-scripts` hashes build bundles.
```yaml
assemble:
  - from: build/static
    into: static

[portlet-slug]-custom-element:
  cssURLs:
    - css/main.*.css
  friendlyURLMapping: [portlet-slug]
  htmlElementName: [portlet-slug]
  instanceable: true
  name: "[Portlet Name] Custom Element"
  portletCategoryName: category.client-extensions
  type: customElement
  urls:
    - js/main.*.js
  useESM: true
```

### 3.4 HTML5 Shell (`public/index.html`)
Required for `react-scripts build` to succeed:
```html
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>[Portlet Name]</title>
  </head>
  <body>
    <div id="root"></div>
  </body>
</html>
```

### 3.5 Web Component Entry Point (`src/index.js`)
Wraps the React application in a standard Web Components Custom Element class:
```javascript
/* global Liferay */
import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './index.css';

class CustomElementComponent extends HTMLElement {
  connectedCallback() {
    if (!this.root) {
      this.root = createRoot(this);
    }
    this.root.render(<App />);
  }

  disconnectedCallback() {
    if (this.root) {
      this.root.unmount();
      this.root = null;
    }
  }
}

const ELEMENT_NAME = '[portlet-slug]';

if (!customElements.get(ELEMENT_NAME)) {
  customElements.define(ELEMENT_NAME, CustomElementComponent);
}
```

### 3.6 Clay CSS & Bootstrap UI Guidelines (No Brittle NPM Imports!)
Liferay's portal theme **already loads Clay CSS & Bootstrap styles natively on the page**. 
- **STRICT RULE:** **DO NOT** import `@clayui/layout`, `@clayui/form`, `@clayui/date-picker`, or `@clayui/button` via npm packages! They introduce version incompatibilities and missing export errors (e.g., `ClayLayout is not exported`, `ClayDatePicker is not exported`).
- **CORRECT PATTERN:** Use standard HTML tags with native Clay / Bootstrap CSS classes:
  - Container / Grid: `<div className="container-fluid-1280">`, `<div className="row">`, `<div className="col-md-6">`
  - Form Groups: `<div className="form-group">`, `<label className="control-label">Label</label>`
  - Inputs: `<input type="text" className="form-control" ... />`
  - Date Inputs: `<input type="date" className="form-control" ... />`
  - Buttons: `<button type="submit" className="btn btn-primary">Save</button>`, `<button type="button" className="btn btn-secondary">Cancel</button>`
  - Tables: `<table className="table table-autofit table-hover table-striped">`
  - Badges & Alerts: `<span className="badge badge-success">Active</span>`, `<div className="alert alert-info">Message</div>`

### 3.7 CSRF-Safe API Service (`src/services/liferayApi.js`)
```javascript
/* global Liferay */

export async function fetchWithAuth(url, options = {}) {
  const defaultHeaders = {
    'Accept': 'application/json',
    'Content-Type': 'application/json',
  };

  const mergedOptions = {
    ...options,
    headers: {
      ...defaultHeaders,
      ...(options.headers || {}),
    },
  };

  // Use Liferay.Util.fetch which handles session cookies and attaches p_auth CSRF token
  if (typeof Liferay !== 'undefined' && Liferay.Util && Liferay.Util.fetch) {
    return Liferay.Util.fetch(url, mergedOptions);
  }
  return fetch(url, mergedOptions);
}

export async function getObjectEntries(objectContextPath, filter = '') {
  let url = `${objectContextPath}?pageSize=20`;
  if (filter) {
    url += `&filter=${encodeURIComponent(filter)}`;
  }
  const res = await fetchWithAuth(url);
  if (!res.ok) throw new Error(`HTTP error: ${res.status}`);
  return res.json();
}

export async function createObjectEntry(objectContextPath, payload) {
  const res = await fetchWithAuth(objectContextPath, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`Failed to create entry: ${res.status}`);
  return res.json();
}
```

### 3.8 Entity List View & Row Actions Pattern
When generating list/table components (e.g. `ListView.js` / `AssignmentList.js`):
- **STRICT ACTION BUTTON RULE:** Every entity row MUST have functional **Edit** and **Delete** actions with working `onClick` handlers!
- **NEVER** render an unwired, static "Actions" button that does nothing when clicked!
- **RECOMMENDED PATTERN:**
```jsx
function ListView({ items, onEdit, onDelete, onAdd }) {
  return (
    <div className="card">
      <div className="card-header d-flex justify-content-between align-items-center">
        <h3 className="card-title mb-0">Records</h3>
        <button className="btn btn-primary btn-sm" onClick={onAdd}>+ Add</button>
      </div>
      <div className="card-body p-0">
        <table className="table table-autofit table-hover mb-0">
          <thead>
            <tr>
              <th>Title</th>
              <th>Description</th>
              <th className="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {items.map(item => (
              <tr key={item.id}>
                <td>{item.title}</td>
                <td>{item.description}</td>
                <td className="text-right">
                  <button className="btn btn-sm btn-secondary mr-2" onClick={() => onEdit(item)}>
                    Edit
                  </button>
                  <button className="btn btn-sm btn-outline-danger" onClick={() => onDelete(item.id)}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
```

---

## 4. Object Action Generation (Replacing Model Listeners / Service Wrappers / LMB)

Depending on Leonardo's classification, Michelangelo either generates **Native Object Actions** inside the Batch Client Extension or creates a dedicated **Object Action Client Extension** microservice.

### 4.1 Native Object Action Generation (Batch JSON)
When the action matches built-in Liferay capabilities (`updateObjectEntry`, `addObjectEntry`, `notification`, `webhook`), generate `batch/01-02-[entity-slug]-actions.batch-engine-data.json`:

```json
{
  "configuration": {
    "className": "com.liferay.object.admin.rest.dto.v1_0.ObjectAction",
    "parameters": {
      "createStrategy": "UPSERT",
      "updateStrategy": "UPDATE"
    }
  },
  "items": [
    {
      "active": true,
      "description": "Send notification or update entry on creation",
      "externalReferenceCode": "ACTION_[ENTITY]_[ACTION_NAME_UPPER]",
      "label": {
        "en_US": "Notify on Entry Add"
      },
      "name": "[actionName]",
      "objectActionExecutorKey": "notification",
      "objectActionTriggerKey": "onAfterAdd",
      "objectDefinitionExternalReferenceCode": "OBJ_[ENTITY_NAME_UPPER]",
      "parameters": {
        "notificationTemplateExternalReferenceCode": "TEMPLATE_USER_NOTIFICATION"
      }
    },
    {
      "active": true,
      "description": "Trigger external webhook on record update",
      "externalReferenceCode": "ACTION_[ENTITY]_WEBHOOK_SYNC",
      "label": {
        "en_US": "Webhook Outbound Sync"
      },
      "name": "outboundSyncWebhook",
      "objectActionExecutorKey": "webhook",
      "objectActionTriggerKey": "onAfterUpdate",
      "objectDefinitionExternalReferenceCode": "OBJ_[ENTITY_NAME_UPPER]",
      "parameters": {
        "url": "https://api.external-system.com/webhook",
        "secret": "optional-shared-secret"
      }
    }
  ]
}
```

*Supported `objectActionExecutorKey` values:*
- `"notification"`: Triggers an in-app or email notification.
- `"webhook"`: Dispatches the event JSON directly to a URL.
- `"updateObjectEntry"`: Updates values in the current or related Object entry.
- `"addObjectEntry"`: Creates a new entry in a related or target Object.

---

### 4.2 Custom Object Action Client Extension (Microservice)
When complex algorithms, proprietary libraries, or multi-step external orchestrations are required, Michelangelo scaffolds a dedicated microservice:

#### 4.2.1 `client-extension.yaml` Definition
```yaml
[entity-slug]-action:
  name: "[Entity Name] Action Handler"
  type: objectAction
  oAuthApplicationHeadlessServer: [entity-slug]-action-oauth
  resourcePath: /api/action

[entity-slug]-action-oauth:
  name: "[Entity Name] Action OAuth"
  type: oAuthApplicationHeadlessServer
  .serviceAddress: localhost:8080
  .serviceScheme: http
  scopes:
    - Liferay.Object.Admin.REST.everything
```

#### 4.2.2 Action Payload Contract (Spring Boot / Node.js Microservice)
```json
{
  "companyId": 20101,
  "entryDTO": {
    "id": 1001,
    "externalReferenceCode": "EMP_1001",
    "values": {
      "fullName": "John Doe",
      "email": "john.doe@company.com"
    }
  },
  "model": "com.liferay.object.model.ObjectDefinition#[id]",
  "transitionName": "onAfterAdd"
}
```

---

## 5. Configuration Client Extension Generation (Replacing OSGi Configurations)

When an OSGi module used `@ExtendedObjectClassDefinition` or `@Meta.OCD`, Michelangelo generates a **Configuration Client Extension**:

### 5.1 `client-extension.yaml` Definition
```yaml
[module-slug]-configuration:
  name: "[Module Name] Configuration"
  type: configuration
  scope: company # or site / system
  configurationPid: "com.sample.configuration.[Module]Configuration"
```

---

## 6. System Object Extensions (Replacing Expando / Custom Fields)

When legacy modules used `getExpandoBridge()` on system entities (e.g., `User`, `AccountEntry`), Michelangelo adds a batch definition targeting the system object's ERC:

```json
{
  "configuration": {
    "className": "com.liferay.object.admin.rest.dto.v1_0.ObjectField",
    "parameters": {
      "createStrategy": "UPSERT"
    }
  },
  "items": [
    {
      "DBType": "String",
      "businessType": "Text",
      "externalReferenceCode": "FIELD_USER_EMPLOYEE_NUMBER",
      "indexed": true,
      "indexedAsKeyword": true,
      "label": { "en_US": "Employee Number" },
      "name": "employeeNumber",
      "objectDefinitionExternalReferenceCode": "L_USER",
      "required": false
    }
  ]
}
```

---

## 7. Job Scheduler Template OSGi Module Generation (`BaseDispatchTaskExecutor`)

When legacy code contains scheduled background jobs (`SchedulerEntry`, Quartz `MessageListener`), and the customer retains background jobs inside Liferay, Michelangelo generates a modernized **Job Scheduler Template OSGi module**:

### 7.1 Module Layout (`modules/[task-slug]-dispatch/`)
```
modules/[task-slug]-dispatch/
├── bnd.bnd
├── build.gradle
└── src/main/java/com/sample/dispatch/executor/
    └── [TaskName]DispatchTaskExecutor.java
```

### 7.2 `bnd.bnd`
```properties
Bundle-Name: [Task Name] Dispatch Task Executor
Bundle-SymbolicName: com.sample.dispatch.[task-slug]
Bundle-Version: 1.0.0
```

### 7.3 `build.gradle`
```groovy
dependencies {
    compileOnly group: "com.liferay", name: "com.liferay.dispatch.api"
    compileOnly group: "com.liferay.portal", name: "com.liferay.portal.kernel"
    compileOnly group: "org.osgi", name: "org.osgi.service.component.annotations"
}
```

### 7.4 `[TaskName]DispatchTaskExecutor.java` Template
```java
package com.sample.dispatch.executor;

import com.liferay.dispatch.executor.BaseDispatchTaskExecutor;
import com.liferay.dispatch.executor.DispatchTaskExecutor;
import com.liferay.dispatch.executor.DispatchTaskExecutorOutput;
import com.liferay.dispatch.model.DispatchTrigger;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;

import org.osgi.service.component.annotations.Component;

@Component(
    property = {
        "dispatch.task.executor.name=[Task Display Name]",
        "dispatch.task.executor.type=[task-type-key]"
    },
    service = DispatchTaskExecutor.class
)
public class [TaskName]DispatchTaskExecutor extends BaseDispatchTaskExecutor {

    @Override
    public void doExecute(
            DispatchTrigger dispatchTrigger,
            DispatchTaskExecutorOutput dispatchTaskExecutorOutput)
        throws Exception {

        _log.info("Executing scheduled task: " + dispatchTrigger.getName());

        // Read runtime trigger settings / properties if needed:
        // UnicodeProperties properties = dispatchTrigger.getDispatchTaskSettingsUnicodeProperties();

        try {
            // [Migrated Business Logic from legacy MessageListener.receive()]
            
            dispatchTaskExecutorOutput.setOutput("Task executed successfully.");
        }
        catch (Exception e) {
            _log.error("Error executing task", e);
            throw e; // Triggers FAILED status in Job Scheduler history
        }
    }

    private static final Log _log = LogFactoryUtil.getLog([TaskName]DispatchTaskExecutor.class);
}
```
*Benefits:*
- Zero hardcoded Quartz cron configurations in code or config files.
- Administrators schedule triggers, manage execution frequencies, and inspect run status directly in Liferay *Control Panel $\rightarrow$ System $\rightarrow$ Job Scheduler*.

---

## 8. Front-End Look & Feel Client Extensions Generation

When Leonardo blueprints a Look & Feel modernization, Michelangelo generates the corresponding client extension project under `/client-extensions/`:

### 8.1 Theme CSS Client Extension (`type: themeCSS`)
Replaces legacy Theme WAR styles and exposes Style Book design tokens:
```yaml
assemble:
  - from: build/css
    into: static/css

[brand-slug]-theme-css:
  name: "[Brand Name] Theme CSS"
  type: themeCSS
  clayURL: static/css/clay.css
  mainURL: static/css/main.css
  frontendTokenDefinitionJSON: frontend-token-definition.json
  scope: layout # or controlPanel
```

### 8.2 Global CSS Client Extension (`type: globalCSS`)
Injects styling across pages without replacing the theme:
```yaml
assemble:
  - from: css
    into: static/css

[brand-slug]-global-css:
  name: "[Brand Name] Global Styles"
  type: globalCSS
  url: static/css/global.css
  scope: layout # or company
```

### 8.3 Global JS Client Extension (`type: globalJS`)
Injects analytics, tracking, or global polyfill scripts:
```yaml
assemble:
  - from: js
    into: static/js

[brand-slug]-global-js:
  name: "[Brand Name] Global Scripts"
  type: globalJS
  url: static/js/global.js
  scope: company # or layout
  scriptLocation: head # or bottom
  scriptElementAttributes:
    async: true
    defer: true
```

### 8.4 Theme Favicon & Spritemap Client Extensions
Overrides site favicons or Clay SVG icon maps:
```yaml
assemble:
  - from: images
    into: static/images

[brand-slug]-favicon:
  name: "[Brand Name] Favicon"
  type: themeFavicon
  url: static/images/favicon.ico

[brand-slug]-spritemap:
  name: "[Brand Name] Icon Spritemap"
  type: themeSpritemap
  url: static/images/icons.svg
```

### 8.5 Editor Config Contributor Client Extension (`type: editorConfigContributor`)
Replaces legacy Java `EditorConfigContributor` components:
```yaml
assemble:
  - from: js
    into: static/js

[brand-slug]-editor-config:
  name: "Custom CKEditor Config"
  type: editorConfigContributor
  url: static/js/editor-config.js
  editorConfigKeys:
    - rich_text
  editorNames:
    - ckeditor5
```
*Script boilerplate (`js/editor-config.js`):*
```javascript
export default function (config) {
  // Modify CKEditor configuration (e.g. remove or add toolbar items)
  if (config.toolbar) {
    config.toolbar.items = config.toolbar.items.filter(item => item !== 'uploadImage');
  }
  return config;
}
```

### 8.6 IFrame Client Extension (`type: iframe`)
Replaces legacy IFrame portlets or portlets wrapping external applications:
```yaml
[tool-slug]-iframe:
  name: "[Tool Name] Embedded Dashboard"
  type: iframe
  url: https://tools.company.com/dashboard
  portletCategoryName: category.client-extensions
  instanceable: true
```

---

## 9. Theme WAR Modernization & Fragment Generation

When Michelangelo decomposes a Theme WAR or Widget Template (ADT), the following artifacts are generated:

### 9.1 Style Book Design Tokens (`frontend-token-definition.json`)
Extracted from `_clay_variables.scss` and `partials/variables/_colors.scss`:
```json
{
  "frontendTokenCategories": [
    {
      "name": "Brand Colors",
      "frontendTokenSets": [
        {
          "name": "Theme Colors",
          "frontendTokens": [
            {
              "label": "Primary Color",
              "name": "primary",
              "type": "Color",
              "value": "#0B5FFF"
            },
            {
              "label": "Secondary Color",
              "name": "secondary",
              "type": "Color",
              "value": "#6B6C7E"
            },
            {
              "label": "Dark Background",
              "name": "dark",
              "type": "Color",
              "value": "#272833"
            }
          ]
        }
      ]
    },
    {
      "name": "Typography",
      "frontendTokenSets": [
        {
          "name": "Fonts",
          "frontendTokens": [
            {
              "label": "Font Family Base",
              "name": "fontFamilyBase",
              "type": "FontFamily",
              "value": "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
            }
          ]
        }
      ]
    }
  ]
}
```

### 9.2 Header & Footer Page Fragments (from `header.ftl` & `footer.ftl`)
Placed into a fragment collection (e.g. `src/fragments/theme-fragments/`):
```
fragments/theme-fragments/
├── collection.json
├── header-navigation/
│   ├── fragment.json
│   ├── index.html
│   └── index.css
└── footer/
    ├── fragment.json
    ├── index.html
    └── index.css
```

#### `header-navigation/fragment.json`:
```json
{
  "fragmentEntryKey": "header-navigation",
  "name": "Site Header & Navigation",
  "type": "component",
  "htmlPath": "index.html",
  "cssPath": "index.css",
  "jsPath": "index.js"
}
```

#### `header-navigation/index.html` (Extracted from FreeMarker with editable tags):
```html
<header class="bg-primary header py-2 text-white" role="banner">
  <div class="container d-flex align-items-center justify-content-between">
    <a class="navbar-brand text-reset d-flex align-items-center" href="/">
      <img alt="Logo" class="mr-2" data-lfr-editable-id="site-logo" data-lfr-editable-type="image" src="[theme-logo.png]" height="40" />
      <span class="font-weight-bold" data-lfr-editable-id="site-title" data-lfr-editable-type="text">Livingstone Hotels</span>
    </a>
    <div class="navigation-slot">
      <lfr-drop-zone></lfr-drop-zone>
    </div>
  </div>
</header>
```

### 9.3 Collection Display Fragments (Replacing Legacy ADTs)
Converts FreeMarker ADTs (e.g. `ap-blog-presentation-template.ftl`) into a reusable Page Fragment intended for Collection Displays:
```html
<div class="card h-100 shadow-sm border-0">
  <div class="aspect-ratio card-item-first">
    <img alt="Thumbnail" class="aspect-ratio-item" data-lfr-editable-id="cover-image" data-lfr-editable-type="image" src="" />
  </div>
  <div class="card-body d-flex flex-column">
    <h3 class="card-title h5 font-weight-bold" data-lfr-editable-id="entry-title" data-lfr-editable-type="text">Post Title</h3>
    <p class="card-text text-muted small" data-lfr-editable-id="entry-date" data-lfr-editable-type="text">October 5, 2026</p>
    <p class="card-text flex-grow-1" data-lfr-editable-id="entry-summary" data-lfr-editable-type="rich-text">Post summary goes here...</p>
    <a class="btn btn-outline-primary btn-sm mt-3" data-lfr-editable-id="read-more-link" data-lfr-editable-type="link" href="#">Read More</a>
  </div>
</div>
```
*Benefits:* Zero FreeMarker Java backend calls; visual content linking via Liferay Page Editor.




