# Leonardo (The Architect) Playbook: System Mapping & Blueprinting Rules

## 1. Mission & Scope
The Architect agent (Leonardo) transforms the raw analysis (`legacy-analysis.json`) into concrete architectural blueprints for modern Liferay DXP:
1. **Service Builder Entities $\rightarrow$ Liferay Object Definitions** (packaged inside a **Batch Client Extension**).
2. **Custom Java Business Logic $\rightarrow$ Liferay Object Validations, Object Actions, or Server CETs**.
3. **JSP / Portlet Views $\rightarrow$ Frontend Custom Element Client Extensions** (React + Clay UI).

---

## 2. Service Builder to Liferay Objects Type Mapping Matrix

| Service Builder Column Type | Liferay Object `DBType` | Liferay Object `businessType` | Mandatory Settings / Notes |
| :--- | :--- | :--- | :--- |
| `String` (length $\le$ 255) | `String` | `Text` | Default text field. Set `indexedAsKeyword: true` if used in finders/filtering. |
| `String` (length > 255 or text) | `String` | `LongText` | For descriptions, notes, comments. Do not set `indexedAsKeyword: true`. |
| `int`, `Integer` | `Integer` | `Integer` | Standard 32-bit integer. |
| `long`, `Long` (non-PK, non-FK)| `Long` | `PrecisionDecimal` or `Integer` | 64-bit integer. |
| `double`, `Double`, `float` | `Double` | `Decimal` | Floating point numbers (currency, percentages). |
| `boolean`, `Boolean` | `Boolean` | `Boolean` | True/false toggles. |
| `Date` | `Date` | `Date` | **CRITICAL**: MUST include `"objectFieldSettings": [{"name": "timeStorage", "value": "convertToUTC"}]`. |
| `Clob` | `Clob` | `RichText` | HTML / rich-text content. |
| `Blob` | Attachment / Media | `Attachment` | Store as file attachment or migrate to Document Library with ERC reference. |

### 2.1 Audit & System Fields Handling
Liferay Service Builder modules routinely declare:
- `companyId`, `groupId`, `userId`, `userName`, `createDate`, `modifiedDate`

**STRICT RULE:**
Do **NOT** include these fields in `objectFields`! Liferay Objects automatically creates and populates these audit fields on every Object Definition as built-in system fields. Re-adding them causes schema conflicts and batch deployment failures.

### 2.2 Localized Fields
If a Service Builder column has `localized="true"`, Liferay Objects handles this globally (per-field localization is not directly toggleable in batch JSON). 
- Ensure the Batch JSON handles payload dictionaries: `{"en_US": "Title", "es_ES": "Titulo"}` for `String` fields when mapping legacy data.

### 2.3 Primary Keys & External Reference Codes
- Service Builder uses synthetic numeric keys (e.g., `employeeId`).
- Liferay Objects provides its own numeric `id` and a string `externalReferenceCode` (ERC).
- **Migration Mapping**:
  - Legacy `[entity]Id` should be mapped to the Object's `externalReferenceCode` (e.g., `EMP-10023` or raw string ID).
  - Optionally, define an optional field `legacyId` (`Long` / `Integer`) if legacy systems need to search by the old numeric identifier.

### 2.4 Foreign Keys & Relationship Mapping
When an entity references another entity (`departmentId` referencing `Department`):
- Do **NOT** create a raw numeric `departmentId` field.
- Instead, define a **Liferay Object Relationship**:
  - `deletionType`: `cascade` or `disallow`.
  - `type`: `oneToMany` (Parent: Department, Child: Employee) or `manyToMany`.
  - In batch definitions, relationship files are defined with numeric ordering `02-00-relationship-definition.batch-engine-data.json`.

### 2.5 Service Builder Finders to REST OData Filters
Service Builder finders (e.g., `findByDepartmentIdAndStatus`) map directly to Liferay Headless Object REST APIs:
- Ensure the queried fields have:
  - `"indexed": true`
  - `"indexedAsKeyword": true` (for string/categorical fields)
- The resulting REST query becomes:
  `GET /o/c/employees?filter=(r_departmentToEmployee_c_departmentId eq 123) and (status eq 'active')`

---

## 3. Java Business Logic & Advanced OSGi Patterns Modernization Rules

### 3.1 Model Listeners & Business Actions Decision Framework
When legacy code contains an OSGi `ModelListener` or custom persistence logic in `LocalServiceImpl`/`ServiceWrapper`, Leonardo applies this **Decision Tree** to determine whether to use a **Native Object Action** (zero code) or a **Custom Object Action Client Extension** (microservice):

```
                        [ Legacy Java Logic Detected ]
                                      │
           ┌──────────────────────────┴──────────────────────────┐
           ▼                                                     ▼
[ Matches Liferay Native Action? ]                      [ Requires Custom Code? ]
           │                                                     │
   ┌───────┼───────────────────────┬──────────────┐              │
   ▼       ▼                       ▼              ▼              ▼
Update    Add Entry              Send           Webhook     Object Action
Entry     in Related Object    Notification   (HTTP Call)   Client Extension
(Native)  (Native)               (Native)       (Native)       (Microservice)
```

#### Detailed Transformation Matrix for Model Listeners & Logic:

| Action Detected | Target Liferay Modern Architecture | Implementation Details |
| :--- | :--- | :--- |
| **Updating Fields in current or related entry** (e.g. updating a counter, setting a related flag) | **Native Object Action: "Update an Object Entry"** | Trigger: `onAfterAdd` or `onAfterUpdate`.<br>Action Executor: `updateObjectEntry`.<br>Zero Java code required; defined directly in the Object definition batch JSON. |
| **Creating an entry in another/related Object** (e.g. creating an audit row, creating a child record) | **Native Object Action: "Add an Object Entry"** | Trigger: `onAfterAdd` or `onAfterUpdate`.<br>Action Executor: `addObjectEntry`.<br>Zero Java code required; maps incoming payload fields to target Object. |
| **Sending an Email / In-App Notification** (e.g. `MailService.sendEmail(...)`, `UserNotificationEvent`) | **Native Object Action: "Send a Notification"** | Trigger: `onAfterAdd` or `onAfterUpdate`.<br>Action Executor: `notification`.<br>Uses Liferay's built-in Notification Templates and user routing. |
| **Standard HTTP / REST Notification** (e.g. simple POST to an external endpoint) | **Native Object Action: "Webhook"** | Trigger: `onAfterAdd` or `onAfterUpdate`.<br>Action Executor: `webhook`.<br>Sends the JSON payload directly to the configured endpoint URL without hosting an intermediate service. |
| **Logging & Simple Auditing** (e.g. `_log.info(...)`, `System.out.println(...)`) | **Built-in Object Audit / Entry History** | **NO ACTION NEEDED**: Enable `"enableObjectEntryHistory": true` in the Object Definition batch JSON. Liferay automatically logs every change, author, and timestamp. |
| **Complex Business Logic** (e.g. PDF generation, multi-system orchestration, legacy SOAP/JDBC calls, complex algorithms) | **Custom Object Action Client Extension** (`type: objectAction`) | Deploy a standalone microservice (Spring Boot, Node.js, Python) that receives the action payload via HTTP POST and handles the processing. |

### 3.2 General OSGi Component Modernization Matrix

| Legacy OSGi Pattern | Modern Liferay Architecture | Migration Strategy & Action |
| :--- | :--- | :--- |
| **Field validations** (e.g., `<aui:validator>`, `validate(...)`, `ServiceWrapper` validation) | **Object Validation Rule** | Configured in Object Definition using Expression Builder or Groovy script (e.g., `salary > 0` or JS Regex translated to Groovy). |
| **Default values / Slug generation** | **Object Action** (`onBeforeAdd` or `onAfterAdd` trigger) or Expression Builder. | Automatically set calculated attributes or normalize fields. |
| **Search Contributors & Indexer Post Processors** | **Native Liferay Objects Search** | **RETIRE**: Liferay Objects automatically indexes all defined fields (`indexed: true`, `indexedAsKeyword: true/false`). No custom Java indexer classes needed! |
| **Message Bus (LMB) Event Consumers** (`MessageListener`, `@Async`) | **Native Object Actions** or **Object Action Client Extensions** | Map fire-and-forget events to Object Actions (Webhooks, notifications, or microservices). |
| **Legacy Scheduled Jobs** (`SchedulerEntry`, Quartz `MessageListener`) | **Job Scheduler Template OSGi Module (`BaseDispatchTaskExecutor`)** | Modernize into a standard Liferay **Job Scheduler Template** (`BaseDispatchTaskExecutor`).<br>- Replaces hardcoded Quartz/cron registrations.<br>- Appears natively under *Control Panel $\rightarrow$ System $\rightarrow$ Job Scheduler* with UI trigger management and centralized execution logs.<br>- *(Optional Cloud Alternative)*: Can be extracted into an external Cron container calling Liferay Headless REST/Batch APIs via OAuth 2.0. |
| **OSGi Configurations** (`@Meta.OCD`, `ConfigurationAdmin`) | **Configuration Client Extension** or **Custom Element Attributes** | 1. System/Instance config: map to `type: configuration` CET.<br>2. Portlet instance settings: pass as configurable attributes to the Custom Element HTML tag. |
| **Custom Fields / Expando** (`getExpandoBridge()`) | **System Object Extensions** (Liferay Objects) | Liferay 7.4+ allows extending system objects (User, Organization) with custom fields directly in Liferay Objects, deployable via Batch Client Extensions. |
| **Permission checks** (`resourceLocalService`, `ModelResourcePermission`) | **Liferay Object Roles & Permissions** | Managed natively in the UI. No custom permission checking logic is needed. |
| **Workflow / Status Fields** (`status`, `statusByUserId`) | **Native Workflow Integration** | Drop legacy status fields. Enable `"enableObjectEntryDraft": true` in Object JSON if approval processes are needed. |

---

## 4. UI Layer (`*Portlet.java` & JSPs) Extraction Rules

### 4.1 Framework Selection
Migrate legacy JSPs to **React Custom Element Client Extensions**:
- Standard component stack: **React 18/19 + Clay UI (`@clayui/core`, `@clayui/form`)**.
- Uses modern ES modules and the Liferay frontend container.

### 4.2 Form Mapping
| Legacy JSP / AUI Component | Modern React / Clay Equivalent |
| :--- | :--- |
| `<aui:form action="..."/>` | `<ClayForm>` calling REST endpoint `/o/c/[object]` |
| `<aui:input name="title" />` | `<ClayInput>` with controlled React state |
| `<aui:select name="status">` | `<ClaySelect>` with options |
| `<clay:management-toolbar>` | `<ClayManagementToolbar>` |
| `<liferay-ui:search-container>` | `<ClayTable>` or `<ClayPaginationBar>` fetching data via REST |

### 4.3 Asset Publisher & Display Pages Mapping
If the legacy module relied heavily on `AssetRenderer` and the Asset Publisher:
- Enable `"enableCategorization": true` on the Object Definition to retain Tags/Categories.
- Migrate `abstract.jsp` to a **Collection Display Widget + Page Fragment** mapping Object fields.
- Migrate `full_content.jsp` to a **Display Page Template (DPT)** bound to the Object.
- *Rule:* Avoid rebuilding complex list/detail views in React if native Liferay tools (DPT/Collection Display) can render the Object data natively.

### 4.4 Traditional Theme WAR Decomposition Architecture

Traditional Theme WARs are monolithic bundles combining skeleton HTML, navigation, CSS stylesheets, SASS variables, images, and JavaScript. In modern Liferay (7.4+), Leonardo decomposes a Theme WAR into 5 independent, modular, low-code/no-code pieces:

```
                            [ Legacy Theme WAR ]
                                      │
     ┌──────────────────┬─────────────┼───────────────┬──────────────────┐
     ▼                  ▼             ▼               ▼                  ▼
[portal_normal.ftl]  [_clay_variables] [_custom.scss] [Color Schemes]   [Static Assets]
     │                  │             │               │                  │
     ▼                  ▼             ▼               ▼                  ▼
Master Page Template   Style Book   themeCSS Client  Style Books        themeFavicon &
+ Header/Footer        Tokens       Extension        (per scheme)       themeSpritemap
Page Fragments         (JSON)
```

#### Theme Decomposition Matrix:
| Legacy Theme WAR File | Modern Architecture Target | Modern Deliverable & Role |
| :--- | :--- | :--- |
| **`templates/portal_normal.ftl`** | **Master Page Template** | Defines the global page frame (`<header>`, `<main>`, `<footer>`) with a central **Drop Zone** for content page components. |
| **`templates/header.ftl` & `navigation.ftl`** | **Header & Navigation Page Fragment** | Reusable Page Fragment containing the responsive navbar, logo, site name, and navigation menu widget. Marketers can edit links and logo visually in the Page Editor. |
| **`templates/footer.ftl` & `social_media.ftl`** | **Footer Page Fragment** | Reusable Page Fragment containing copyright, footer links, and social media icons with editable text attributes (`data-lfr-editable-id`). |
| **`css/_clay_variables.scss`** | **Style Book Tokens (`frontend-token-definition.json`)** | Extracts brand colors (`$primary`, `$secondary`), typography (`$font-family-base`), and spacing into design tokens so designers can customize them visually without redeploying code. |
| **`css/clay.scss` & `_custom.scss`** | **`themeCSS` Client Extension** | Compiled `clay.css` and `main.css` packaged in a Client Extension that replaces default theme stylesheets across the site or layout. |
| **`liferay-look-and-feel.xml` `<color-scheme>`** | **Liferay Style Books** | Each legacy color scheme (e.g., Light, Dark, Blue) becomes a dedicated **Style Book** preset sharing the same `themeCSS` tokens with different variable values. |
| **`images/favicon.ico`** | **`themeFavicon` Client Extension** | Site favicon deployed via `client-extension.yaml` (`type: themeFavicon`). |
| **`images/lexicon/icons.svg`** | **`themeSpritemap` Client Extension** | Custom SVG icon spritemap deployed via `client-extension.yaml` (`type: themeSpritemap`). |
| **`js/main.js`** | **`globalJS` CET** or **Fragment JS** | Global interaction scripts (e.g. scroll listeners) become `globalJS`, while component-specific JS (e.g. mobile drawer toggle) is encapsulated directly inside the Header Fragment. |
| **`layouttpl/*.tpl` (Layout Templates)** | **Native Content Page Containers** | **RETIRE**: Content Pages provide responsive multi-column Grid and Flexbox containers visually. Custom layout template plugins are obsolete. |

---

### 4.5 Widget Templates (Application Display Templates - ADT) Modernization

Legacy FreeMarker ADTs (`*.ftl`) customized native Liferay widgets using Java model objects (`curEntry.getAssetRenderer()`, `themeDisplay`). Leonardo migrates them to modern, declarative page-building tools:

| Legacy Widget Template (ADT) | Modern Liferay Target | Migration Strategy |
| :--- | :--- | :--- |
| **Asset Publisher List / Grid ADT** (e.g. Blog cards, News list) | **Collection Display + Page Fragment** | 1. Replace Asset Publisher with the native **Collection Display** widget connected to an Asset/Object Collection.<br>2. Convert the FreeMarker card/list markup into a reusable **Page Fragment**.<br>3. Bind fragment fields (`title`, `image`, `date`, `summary`) visually to collection item attributes. |
| **Asset Publisher Full Content ADT** (single item view) | **Display Page Template (DPT)** | Create a Display Page Template mapped to the target content type (e.g. Blog, Web Content, Object). Fields are linked declaratively without FreeMarker Java-interop code. |
| **Navigation Menu ADT** (e.g. vertical sidebar menu, accordion) | **Navigation Menu Fragment** or **Custom Element** | Style the native Navigation Menu widget using modern CSS / Style Books or create a lightweight Navigation Page Fragment. |
| **Media Gallery ADT** (e.g. image carousel, photo gallery) | **Media Gallery Fragment / Collection Display** | Map documents directly into a carousel or gallery fragment. |

---

### 4.6 Front-End Look & Feel Modernization Matrix (Client Extensions Summary)

| Legacy Front-End Artifact | Modern Client Extension Type | Implementation Blueprint & Rules |
| :--- | :--- | :--- |
| **Theme WARs** (`_custom.scss`, `_clay_variables.scss`) | **`themeCSS`** | - Replaces `clay.css` and `main.css`.<br>- Includes `frontendTokenDefinitionJSON` to expose brand variables (colors, typography) into Liferay Style Books.<br>- Set `scope: layout` (page/site design) or `scope: controlPanel`. |
| **Standalone CSS files** (`header-portlet-css` / page styles) | **`globalCSS`** | - Injects CSS without replacing the theme.<br>- Use `scope: layout` for page-specific styles or `scope: company` for instance-wide CSS. |
| **Global Scripts & Trackers** (`footer-portlet-javascript`, analytics) | **`globalJS`** | - Injects scripts globally.<br>- Use `scope: company` and `scriptLocation: head` (for fonts/analytics) or `bottom` (for defer-loaded scripts).<br>- Declare script attributes via `scriptElementAttributes: { async: true, defer: true }`. |
| **Custom Favicon** (`images/favicon.ico`) | **`themeFavicon`** | - Replaces site favicon via `url: images/favicon.ico` without building a theme. |
| **Custom SVG Icons** (`images/lexicon/icons.svg`) | **`themeSpritemap`** | - Replaces Clay icon set via `url: images/icons.svg`. |
| **CKEditor Customizations** (`EditorConfigContributor.java`) | **`editorConfigContributor`** | - Migrates Java config classes to a JavaScript configuration script.<br>- Targets specific editor keys (`editorConfigKeys: [rich_text]`) and editors (`editorNames: [ckeditor5]`). |
| **IFrame Portlets** (external app wrappers) | **`iframe`** | - Eliminates Java wrapper code entirely.<br>- Points `url: https://external-app.com` directly into an iframe widget with `instanceable: true/false`. |
| **Search Container Cell Customizers** | **`fdsCellRenderer`** / **`fdsFilter`** | - Replaces custom `<liferay-ui:search-container-column-text>` formatters with React/JS cell components inside Frontend Data Sets. |
| **Shared Front-End Libraries** (React, Lodash, Day.js) | **`jsImportMapsEntry`** | - Registers shared modules in browser import maps so custom elements and fragments share dependencies without duplicating bundle sizes. |

### 4.7 API Communication & Authentication
- Frontend components **MUST** use `Liferay.Util.fetch` when executing in the browser:
  ```javascript
  const response = await Liferay.Util.fetch('/o/c/employees', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(formData)
  });
  ```
- *Why:* `Liferay.Util.fetch` automatically handles session authentication, cookies, and attaches the CSRF token (`p_auth`), preventing 403 Forbidden errors.

---

## 5. Architectural Deliverables Blueprint
Leonardo outputs the blueprint structure:
```json
{
  "batchClientExtension": {
    "projectName": "employee-object",
    "folderErc": "MIGRATION_OBJECTS_FOLDER",
    "objects": ["Employee", "Department"],
    "relationships": [
      { "name": "departmentToEmployee", "type": "oneToMany", "from": "Department", "to": "Employee" }
    ]
  },
  "customElementClientExtension": {
    "projectName": "employee-manager-custom-element",
    "htmlElementName": "employee-manager",
    "type": "customElement",
    "framework": "react",
    "endpoints": [
      { "path": "/o/c/employees", "methods": ["GET", "POST", "PUT", "DELETE"] }
    ]
  }
}
```
