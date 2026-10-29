# Donatello (The Analyzer) Playbook: Legacy OSGi Extraction Rules

## 1. Mission & Scope
The Analyzer agent (Donatello) inspects legacy Liferay 7.x OSGi modules containing:
- **Service Builder** (`service.xml`, `*LocalServiceImpl.java`, `*ServiceImpl.java`, `*FinderImpl.java`)
- **Portlets & Actions** (`*Portlet.java`, MVCActionCommand, MVCRenderCommand, MVCResourceCommand)
- **UI Templates** (`*.jsp`, `*.jspf`, `init.jsp`)

The goal is to reverse-engineer the domain model, business logic, endpoints, and UI views, producing a normalized JSON payload (`legacy-analysis.json`) following `migration-knowledge/schemas/intermediate-analysis.schema.json`.

---

## 2. Service Builder (`service.xml`) Extraction Rules

### 2.1 Entity Header Parsing
For each `<entity>` in `service.xml`:
- `name`: Capitalized entity name (e.g., `Employee`).
- `table`: Database table name (e.g., `FOO_Employee`).
- `uuid`: Boolean flag (`uuid="true"` indicates Liferay UUID tracking).
- `localService`: Boolean flag.
- `remoteService`: Boolean flag (indicates whether JSON-WS was exposed).
- `packagePath`: The package namespace (e.g., `com.liferay.sample`).

### 2.2 Column Parsing & Classification
For each `<column>` inside `<entity>`:
- **`name`**: Column identifier (e.g., `employeeId`, `departmentId`, `hireDate`, `notes`).
- **`type`**: Java/Service Builder type (`String`, `long`, `int`, `double`, `boolean`, `Date`, `Blob`).
- **`localized`**: Boolean flag (`localized="true"` indicates multi-language support).
- **`primary`**: Boolean flag indicating if this is the primary key.
- **`idType`**: If primary, check `id-type="identity"` or `id-type="increment"`.
- **System Audit Field Detection**:
  Classify fields into standard Liferay audit attributes:
  - `companyId`, `groupId`, `userId`, `userName`, `createDate`, `modifiedDate`
  *Rule:* Mark these as `isSystemField: true`. When migrating to Liferay Objects, these must **not** be recreated as custom fields because Liferay Objects provides them automatically.
- **Workflow & Status Fields**:
  - `status`, `statusByUserId`, `statusByUserName`, `statusDate`
  *Rule:* Mark these as `isWorkflowField: true`. Liferay Objects handles draft/approval status inherently.

### 2.3 Foreign Keys & Relationship Detection
1. **Direct ID Reference**:
   - A column ending in `...Id` (e.g., `departmentId`, `companyId`, `categoryId`) where a matching entity exists indicates a candidate Foreign Key relationship.
2. **Collection / Mapping Table**:
   - Column with `type="Collection"` and `entity="OtherEntity"` or `mapping-table="..."` indicates a 1:M or M:M relationship.
   - Record: `relationType` (`oneToMany` or `manyToMany`), `targetEntity`, and `mappingTable`.

### 2.4 Finders Extraction
For each `<finder>`:
- `name`: Finder name (e.g., `ByDepartmentId`, `ByStatusAndGroupId`).
- `returnType`: `Collection` or single entity.
- `finderColumns`: List of column names used in the query.
*Significance:* In Liferay Objects, these indicate fields that **must** have `indexed: true` and `indexedAsKeyword: true` so they can be queried via REST OData filters (`/o/c/[objects]?filter=...`).

---

## 3. Business Logic (`*LocalServiceImpl.java` & OSGi Components) Extraction Rules

Analyze the AST of `[Entity]LocalServiceImpl.java` and related OSGi components:
1. **CRUD Wrappers vs. Custom Logic**:
   - Standard generated methods (`add[Entity]`, `update[Entity]`, `delete[Entity]`) usually contain custom logic inside the hook before/after persistence.
   - Extract:
     - **Validation Logic**: Calls to `validate(...)`, custom exception throwing (`PortalException`).
     - **Calculated Fields**: Logic that computes derived values (e.g., calculating total cost or generating code strings).
2. **Permission Checks**:
   - Calls to `resourceLocalService.addResources(...)`, `ModelResourcePermission`, `PortletPermissionUtil`, or `PrincipalThreadLocal`.
3. **Asset Framework & Workflow Integration**:
   - Presence of `AssetRenderer`, `AssetRendererFactory`, `WorkflowHandler`, or calls to `assetEntryLocalService.updateEntry`.
   - *Rule:* Record these to trigger `enableCategorization: true` and `enableObjectEntryDraft: true` on the Object blueprint.
4. **Custom Finder / Dynamic Query**:
   - Usage of `DynamicQuery` (e.g., `RestrictionsFactoryUtil.like`), `OrderFactoryUtil`, or `CustomSQLUtil`.

---

## 4. Advanced OSGi Components Extraction Rules

Scan the module for specific OSGi component interfaces:

### 4.1 Service Wrappers (`ServiceWrapper.java`)
- Detect classes extending `*ServiceWrapper` or implementing `ServiceWrapper.class` with `@Component(service = ServiceWrapper.class)`.
- Extract:
  - Wrapped service class (e.g., `UserLocalServiceWrapper`, `JournalArticleLocalServiceWrapper`).
  - Overridden methods (e.g., `addUserWithWorkflow`, `updateStatus`).
  - Extracted intent: Is it validation? Argument manipulation? External notification?

### 4.2 Model Listeners (`ModelListener.java` / `BaseModelListener<T>`)
- Detect classes extending `BaseModelListener<T>` or implementing `ModelListener.class`.
- Extract:
  - Target model `T` (e.g., `User`, `Role`, `CustomEntity`).
  - Implemented events: `onBeforeCreate`, `onAfterCreate`, `onBeforeUpdate`, `onAfterUpdate`, `onBeforeRemove`, `onAfterRemove`, `onBeforeAddAssociation`, `onAfterAddAssociation`.
  - **Granular Semantic Classification of Method Body**:
    Inspect the Java AST inside each event method to identify which of the 6 categories the logic belongs to:
    1. **`UPDATE_ENTRY`**:
       - *Signature*: Calls to `*LocalService.update*`, `*Persistence.update*`, or modifying attributes on related models.
       - *Details*: Extract target entity and modified fields/expressions.
    2. **`ADD_ENTRY`**:
       - *Signature*: Calls to `*LocalService.add*`, `*LocalService.create*`, or adding a related entity.
       - *Details*: Extract target model and initialized values.
    3. **`SEND_NOTIFICATION`**:
       - *Signature*: Calls to `MailService.sendEmail(...)`, `MailMessage`, `UserNotificationEventLocalService`, or portal notification utilities.
       - *Details*: Extract recipient (`user.getEmailAddress()`, admin address), subject template, and body template.
    4. **`WEBHOOK` (Simple HTTP / REST Call)**:
       - *Signature*: Calls to `HttpClient`, `HttpUtil`, `URL.openConnection()`, `WebTarget`, or standard HTTP client libraries without complex data transformations.
       - *Details*: Extract endpoint URL, HTTP method (POST/PUT), and payload payload attributes.
    5. **`AUDIT_OR_LOG`**:
       - *Signature*: Calls strictly to `Log.info/warn/debug`, `System.out.println`, or `AuditMessageFactoryUtil` without altering other entities.
       - *Details*: Flag as candidate for native Object Entry History (`enableObjectEntryHistory: true`).
    6. **`CUSTOM_LOGIC_CET`**:
       - *Signature*: Complex business algorithms, multi-step orchestration, PDF generation (iText/PDFBox), proprietary SDKs, direct JDBC/SQL execution, filesystem operations, or calls requiring internal Java APIs.
       - *Details*: Flag as requiring an **Object Action Client Extension** (microservice).

### 4.3 Search Contributors & Indexer Post Processors
- Detect classes implementing:
  - `ModelDocumentContributor`, `ModelIndexerWriterContributor`
  - `IndexerPostProcessor` (with property `indexer.class.name=...`)
  - `QueryPreFilterContributor`, `HitsProcessor`
- Extract:
  - Target indexed entity.
  - Custom fields added to Elasticsearch document.
  - Query filters or sorting alterations.
  *Significance:* For custom entities migrating to Objects, these are retired because Liferay Objects natively indexes all fields with `"indexed": true`.

### 4.4 Message Bus Listeners & Scheduled Jobs (`MessageListener.java`)
- Detect classes implementing `MessageListener` or extending `BaseMessageListener`:
  1. **Event-Driven Message Listener**:
     - Property: `destination.name=[custom_destination]` (not scheduler dispatch).
     - Identifies asynchronous event consumers.
  2. **Legacy Scheduled Job (Quartz / SchedulerEntry)**:
     - Detects:
       - Implementing/referencing `SchedulerEntry`, `SchedulerEntryImpl`, or `SchedulerEngineHelper`.
       - Listening to `destination.name=liferay/scheduler_dispatch` or `DestinationNames.SCHEDULER_DISPATCH`.
       - Reading cron expressions from OSGi configurations (`@Activate` with `schedulerEngineHelper.register(...)`).
     - Extract:
       - Task name / description.
       - Original Cron expression or interval.
       - Execution logic inside `receive(Message message)` or `doReceive(Message message)`.
     - *Classification:* Flag as `SCHEDULED_JOB`.

### 4.5 OSGi Configurations (`@Meta.OCD` / `@ObjectClassDefinition`)
- Detect interfaces annotated with `@Meta.OCD` or `@ObjectClassDefinition`.
- Detect components referencing `configurationPid` or `@ExtendedObjectClassDefinition(scope = Scope.COMPANY / GROUP / PORTLET_INSTANCE)`.
- Extract configuration properties, default values, and scope.

### 4.6 Custom Fields / Expando (`getExpandoBridge()`)
- Detect usage of `ExpandoBridge`, `ExpandoTableLocalService`, or `ExpandoValueLocalService`.
- Extract target entity and custom attribute names.

---

## 5. UI Layer (`*Portlet.java` & JSPs) Extraction Rules

### 4.1 MVC Commands Extraction
Scan `@Component` annotations in the module:
- `javax.portlet.name`: Portlet ID.
- `mvc.command.name`: Action/Render/Resource command names.
- Map each command method to its underlying service calls.

### 4.2 JSP Parsing Rules
Scan all `.jsp` and `.jspf` files in `META-INF/resources/`:
1. **Form Definitions**:
   - `<aui:form action="<%= actionURL %>" name="fm">`: Identify target action and submitted inputs.
   - `<aui:input name="..." type="..." />`:
     - Map input types: `text`, `textarea`, `select`, `date`, `checkbox`, `file`.
     - Extract validation tags, especially `<aui:validator name="custom">` containing inline JavaScript regex, as these map directly to Liferay Object Validations.
2. **Search Containers / Tables**:
   - `<liferay-ui:search-container>` or `<clay:management-toolbar>`:
     - Identify `results` list source and `total` count.
     - `<liferay-ui:search-container-row>`: Extract displayed columns and attributes.
     - `<liferay-ui:search-iterator>`: Extract actions column (Edit, Delete, View links).
3. **Action & Render URLs**:
   - `<portlet:actionURL name="...">`: Map to form REST actions.
   - `<portlet:renderURL>`: Map to page views / modals.
   - `<portlet:resourceURL id="...">`: Map to AJAX endpoints.
4. **Asset Publisher Views**:
   - If JSPs like `asset/abstract.jsp` or `asset/full_content.jsp` exist, flag them as candidates for Fragment Collection Displays and Display Page Templates (DPT).

---

## 6. Look and Feel & Frontend Customizations Extraction Rules

Scan the module, theme projects, and workspace for legacy theme and UI customization artifacts:

### 6.1 Traditional Theme WAR Dissection
When analyzing a legacy theme module (or WAR file):
1. **XML Metadata (`WEB-INF/liferay-look-and-feel.xml`)**:
   - Extract `theme id`, `theme name`, and template extension (`ftl`).
   - Extract all `<color-scheme id="..." name="...">` definitions, including associated CSS classes or alternate CSS files.
   - Extract `<settings>` (e.g., `show-header`, `show-footer`, configurable toggles).
2. **FreeMarker Layout Dissection (`templates/*.ftl`)**:
   - **`portal_normal.ftl`**:
     - Parse top-level structure: `<head>`, `top_head_include`, `body_top_include`, `wrapper`, `content_include`, `bottom_include`.
     - Locate the main content injection point (`${content_include}`).
   - **Header & Navigation Extraction (`header.ftl`, `navigation.ftl`)**:
     - Extract branding markup: `<img src="${site_logo}" />`, site name, logo link (`${site_default_url}`).
     - Extract responsive navbar markup: toggler button, navbar classes, dropdown structure.
     - Isolate into candidate **Header Page Fragment** and **Navigation Menu Fragment**.
   - **Footer Extraction (`footer.ftl`, `footer_navigation.ftl`, `social_media.ftl`)**:
     - Extract footer content: copyright text, language keys, footer links, social media icons.
     - Isolate into candidate **Footer Page Fragment**.
   - **Global Custom Inclusions (`init_custom.ftl`)**:
     - Extract custom variables, service calls, or helper utilities.
3. **SASS & Theme Variables Extraction (`css/*.scss`)**:
   - Parse `_clay_variables.scss`, `clay.scss`, `_custom.scss`, and `partials/variables/_colors.scss`:
     - Extract Brand Colors: `$primary`, `$secondary`, `$success`, `$info`, `$warning`, `$danger`, `$dark`, `$light`.
     - Extract Typography: `$font-family-base`, `$font-size-base`, `$headings-font-family`, font weights.
     - Extract Component Styles: `$btn-border-radius`, card borders, container max-widths.
     - *Significance:* These variables map directly into Liferay **Style Book Tokens** (`frontend-token-definition.json`) and **`themeCSS`**.
4. **Static Assets (`images/*`)**:
   - Detect `favicon.ico` $\rightarrow$ candidate for **`themeFavicon`**.
   - Detect `icons.svg` (Clay / Lexicon spritemap) $\rightarrow$ candidate for **`themeSpritemap`**.
5. **Interactive JavaScript (`js/main.js`)**:
   - Detect navbar toggling, smooth scrolling, modal triggers, or custom DOM listeners.
   - Categorize whether logic belongs in **`globalJS`** or encapsulated inside Page Fragments.

### 6.2 Widget Templates (Application Display Templates - ADT)
Scan for legacy FreeMarker widget templates (`*.ftl`) stored in modules or exported LAR files:
1. **Target Portlet Identification**:
   - **Asset Publisher**: Uses `entries`, `curEntry.getAssetRenderer()`, `blogEntry = assetRenderer.getAssetObject()`, `assetPublisherHelper.getAssetViewURL(...)`.
   - **Navigation Menu**: Uses `entries as navItem`, `navItem.getBrowsableChildren()`, `navItem.isSelected()`.
   - **Media Gallery**: Uses `curFolder`, `curFileEntry`, `DLAppService`.
   - **Category / Tag Navigation**: Uses `assetCategories`, `assetTags`.
2. **Layout & Visual Structure Extraction**:
   - Visual presentation type: Card grid (`col-md-4 card`), Carousel, Accordion, List with thumbnails, Vertical tree.
   - Extracted attributes: Title, Cover Image, Summary/Description, Date, Author, Tag badges.
   - *Significance:* In modern Liferay, these map directly to **Collection Display Widgets + Page Fragments** or **Display Page Templates (DPT)**!

### 6.3 Layout Templates (`*.tpl` / `*.ftl`)
- Detect custom layout template plugins (`*.tpl` files with Bootstrap grid markup e.g. `portlet-column-content-first`).
- Extract the column proportions (e.g., 50/50, 30/70, 25/25/25/25, 1-2-1).
- *Classification:* Flag for conversion into modern **Content Page Grid & Flexbox Section Containers**.

### 6.4 Portlet-Level CSS and JavaScript Injections
- Scan `WEB-INF/liferay-portlet.xml` or Portlet `@Component` properties for:
  - `<header-portlet-css>` / `<footer-portlet-javascript>`
  - `"com.liferay.portlet.header-portlet-css=/css/main.css"`
  - `"com.liferay.portlet.footer-portlet-javascript=/js/main.js"`
- Extract standalone stylesheets and scripts intended to style specific pages or widgets.

### 6.5 Rich-Text Editor Customizations (`EditorConfigContributor`)
- Detect classes implementing `EditorConfigContributor` or extending `BaseEditorConfigContributor`:
  - Component properties: `editor.name=ckeditor`, `editor.name=alloyeditor`, `editor.config.key=rich_text`, `javax.portlet.name=...`.
- Extract target editor configuration keys, portlet names, and toolbar modifications.

### 6.6 IFrame Portlets
- Detect portlets extending `MVCPortlet` where the view JSP renders an `<iframe src="...">` pointing to an external web tool, or configuring `iframe-url`.
- Extract target URL and instanceability settings.

---

## 7. Output Specification
Donatello produces `legacy-analysis.json` containing:
```json
{
  "moduleName": "com.sample.employee.service",
  "entities": [
    {
      "name": "Employee",
      "tableName": "FOO_Employee",
      "primaryKey": "employeeId",
      "fields": [
        { "name": "employeeId", "type": "long", "isPrimary": true, "isSystemField": false },
        { "name": "firstName", "type": "String", "isPrimary": false, "isSystemField": false },
        { "name": "lastName", "type": "String", "isPrimary": false, "isSystemField": false },
        { "name": "email", "type": "String", "isPrimary": false, "isSystemField": false },
        { "name": "salary", "type": "double", "isPrimary": false, "isSystemField": false },
        { "name": "hireDate", "type": "Date", "isPrimary": false, "isSystemField": false },
        { "name": "departmentId", "type": "long", "isPrimary": false, "isSystemField": false, "candidateForeignKey": "Department" }
      ],
      "finders": [
        { "name": "ByDepartmentId", "columns": ["departmentId"], "returnCollection": true }
      ],
      "validations": [
        { "field": "email", "rule": "VALID_EMAIL_PATTERN" }
      ]
    }
  ],
  "ui": {
    "portletName": "com_sample_employee_portlet",
    "forms": [
      {
        "id": "editEmployee",
        "action": "/employee/edit",
        "fields": ["firstName", "lastName", "email", "salary", "hireDate", "departmentId"]
      }
    ],
    "views": [
      { "id": "viewEmployees", "type": "searchContainer", "entity": "Employee" }
    ]
  }
}
```
