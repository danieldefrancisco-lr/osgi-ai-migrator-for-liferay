# Raphael (The Verifier) Playbook: Quality Assurance & Terminal Validation

## 1. Mission & Scope
The Verifier agent (Raphael) validates all generated artifacts before declaring migration success:
1. **Build Validation**: Validates client extension packaging and frontend builds (`blade gw deploy`, `npm run build`).
2. **Runtime Deployment**: Checks OSGi container and Liferay Batch Engine execution logs.
3. **Endpoint & MCP Verification**: Inspects live Liferay endpoints to confirm Objects are published and functional.
4. **Self-Healing Loop**: Analyzes deployment errors and triggers targeted fixes back to Michelangelo.

---

## 2. Verification Protocol for Batch Client Extensions (Objects)

### 2.1 Deployment Command
Run the deployment from the client extension directory or root workspace:
```bash
blade gw :client-extensions:[entity-slug]-object:deploy
```
Or workspace-wide:
```bash
blade gw deploy
```

### 2.2 Log Inspection (`bundles/tomcat*/logs/catalina.out`)
Verify the following sequence in the logs:
1. **OSGi Bundle Started**:
   ```
   STARTED [entity-slug]-object-batch
   ```
2. **OAuth Application Registered**:
   ```
   Registered OAuth 2.0 application ... [entity-slug]-oauth-server
   ```
3. **Batch Engine Execution**:
   ```
   Processed batch engine import task for [entity-slug]-object
   ```

### 2.3 Headless API / MCP Verification
Verify the Object Definition via Liferay REST or MCP Server:
```http
GET /o/object-admin/v1.0/object-definitions/by-external-reference-code/OBJ_[ENTITY_NAME_UPPER]
```
**Assert:**
- HTTP status: `200 OK`
- `status.code`: `0` (`approved`)
- `restContextPath`: Starts with `/o/c/`
- Every mapped field exists with the expected `DBType` and `businessType`.

---

## 3. Verification Protocol for Custom Element Client Extensions (UI)

### 3.1 Build Check
Run the frontend build:
```bash
cd client-extensions/[portlet-slug]-custom-element
npm run build
```
**Assert:**
- Exit code: 0
- Output files present in `dist/` (e.g., `dist/assets/index.js`, `dist/assets/index.css`)
- Check that paths in `client-extension.yaml` match the actual built assets in `assemble`.

### 3.2 Deployment Check
Deploy the custom element:
```bash
blade gw :client-extensions:[portlet-slug]-custom-element:deploy
```
**Assert:**
- OSGi log: `STARTED [portlet-slug]-custom-element`
- Custom Element is available in Liferay Page Editor under "Client Extensions".

---

## 4. Verification Protocol for Object Actions & Configuration Client Extensions

### 4.1 Object Action CET Verification
1. Deploy the object action: `blade gw :client-extensions:[entity-slug]-action:deploy`
2. Assert log: `STARTED [entity-slug]-action`
3. Trigger an event (e.g. POST to `/o/c/[objects]`) and verify that the microservice receives the webhook payload with HTTP 200.

### 4.2 Configuration CET Verification
1. Deploy configuration: `blade gw :client-extensions:[module-slug]-configuration:deploy`
2. Assert log: `STARTED [module-slug]-configuration`
3. Inspect Liferay *Control Panel → System Settings* to ensure the configuration PID is registered and values populated.

### 4.3 Job Scheduler Template OSGi Module Verification
1. Deploy the module: `blade gw :modules:[task-slug]-dispatch:deploy`
2. Assert log: `STARTED com.sample.dispatch.[task-slug]`
3. Verify in Liferay *Control Panel $\rightarrow$ System $\rightarrow$ Job Scheduler*:
   - Click "Add" or "New Job Scheduler Trigger".
   - Confirm that `[Task Display Name]` appears in the Task Type dropdown.
   - Run a test execution manually and confirm status moves to `SUCCESSFUL` in the run history log.

### 4.4 Look & Feel Client Extension Verification (`themeCSS`, `globalCSS`, `globalJS`, etc.)
1. Deploy the client extension: `blade gw :client-extensions:[brand-slug]-[type]:deploy`
2. Assert log: `STARTED [brand-slug]-[type]`
3. Verification:
   - For `themeCSS`: Navigate to *Site Administration $\rightarrow$ Design $\rightarrow$ Page Design Options* or *Style Books* and confirm the new Theme CSS / tokens are selectable.
   - For `globalCSS` / `globalJS`: Verify in browser DevTools that the `<link>` or `<script>` tag is rendered in `<head>` or before `</body>`.
   - For `editorConfigContributor`: Open Web Content / Journal editor and verify that the customized toolbar layout reflects the config changes.
   - For `iframe`: Place the widget on a content page and confirm that the external URL renders without X-Frame-Options blocking.

### 4.5 Theme Modernization, Master Pages & Fragment Verification
1. Import Fragment Collection:
   - Verify fragments are visible in *Site Administration $\rightarrow$ Design $\rightarrow$ Fragments* under the migrated collection name.
2. Verify Master Page Template:
   - Check *Site Administration $\rightarrow$ Design $\rightarrow$ Page Templates $\rightarrow$ Master Pages*.
   - Confirm Header and Footer fragments are rendered around the Drop Zone.
3. Verify Style Books:
   - Open *Design $\rightarrow$ Style Books*. Verify that token categories (Brand Colors, Typography) display the migrated HEX values and fonts.
4. Verify Collection Display Fragments (former ADTs):
   - Place a Collection Display on a Content Page, select an Asset/Object collection, and assign the migrated card/list fragment. Confirm that fields map and render cleanly.

---

## 5. Self-Healing & Error Remediation Matrix

| Symptom / Log Error | Root Cause | Automated Remediation Action |
| :--- | :--- | :--- |
| `The settings 'timeStorage' are required for object field [fieldName]` | `Date` or `DateTime` field missing UTC storage configuration. | Inject `"objectFieldSettings": [{"name": "timeStorage", "value": "convertToUTC"}]` into the offending field definition in the batch JSON. |
| `indexedLanguageId is not valid for type [type]` | `indexedLanguageId` was defined on a non-String/non-Clob field. | Strip `indexedLanguageId` property completely from that field. |
| `NullPointerException` during client extension deployment | Missing `.serviceAddress` or `.serviceScheme` in OAuth block. | Add `.serviceAddress: localhost:8080` and `.serviceScheme: http` under `oAuthApplicationHeadlessServer` in `client-extension.yaml`. |
| Batch creates object in `draft` status (`status.code: 1` or `2`) | Missing explicit approval status in batch JSON. | Add `"status": {"code": 0, "label": "approved"}` to the root of the item in the batch JSON. |
| `403 Forbidden` on REST calls from Custom Element | Missing CSRF token or session cookie. | Ensure all frontend API calls use `Liferay.Util.fetch` instead of native `window.fetch`. |
| `System field name conflict: createDate / modifiedDate` | System audit fields included in `objectFields`. | Remove all occurrences of `companyId`, `groupId`, `userId`, `userName`, `createDate`, `modifiedDate` from `objectFields`. |
