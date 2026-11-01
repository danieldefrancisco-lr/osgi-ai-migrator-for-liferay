# Legacy Notification & Email Migration to Liferay Native Notification Templates

## 1. Problem Statement & Legacy Architecture
In legacy Liferay 7.x OSGi modules, sending emails or in-app notifications typically required hardcoded Java services:
- `com.liferay.mail.kernel.service.MailServiceUtil.sendEmail(mailMessage)`
- `com.liferay.portal.kernel.service.UserNotificationEventLocalServiceUtil.addUserNotificationEvent(...)`
- Hardcoded email subject and body strings inside MVC action commands or model listeners.
- Custom SMTP socket connections or FreeMarker templates rendered via Java backend calls.

### Drawbacks of Legacy Java Email Logic:
1. **Requires Full Java Recompilation:** Any change to email wording or marketing layout required re-building and re-deploying the OSGi bundle.
2. **Coupled to Custom Code:** Frontend forms (or custom elements) directly invoked Java actions instead of headless event-driven architecture.
3. **Incompatible with Client Extensions:** Pure client extensions (Custom Elements, Batch Objects) do not run server-side Java OSGi services.

---

## 2. Modern Liferay 7.4+ Event-Driven Notification Architecture
In modern Liferay, notifications are completely decoupled from code:
```
[Frontend Custom Element] 
       │
       ▼ (POST /o/c/[objects])
[Liferay Object Entry Created]
       │
       ▼ (Event: onAfterAdd)
[Object Action: Notification]
       │
       ▼
[Notification Template (Email / In-App)]
       │
       ▼
[Recipient (Attendee Email Field)]
```

### 2.1 Core Architectural Components:
1. **Notification Templates (`com.liferay.notification.type.email`)**:
   - Managed centrally in **Control Panel $\rightarrow$ Notifications $\rightarrow$ Notification Templates**.
   - Marketers and administrators visually design HTML/rich-text emails.
   - Built-in multi-language localization (`en_US`, `es_ES`, etc.).
   - Dynamic token replacement syntax: `[%FIRSTNAME%]`, `[%LASTNAME%]`, `[%EMAILADDRESS%]`, `[%TICKETTYPE%]`.
2. **Object Actions (`com.liferay.object.admin.rest.dto.v1_0.ObjectAction`)**:
   - Managed in **Control Panel $\rightarrow$ Objects $\rightarrow$ [Object] $\rightarrow$ Actions** tab.
   - Trigger: `onAfterAdd` (dispatched immediately when a record is submitted).
   - Action Type: `Notification`.
   - Recipient Type: Object field (e.g. `emailAddress`).

---

## 3. Migration Playbook & Mapping Rules

### 3.1 Mapping Table
| Legacy 7.x OSGi Pattern | Modern Liferay DXP 7.4+ Solution | Migration Action |
| :--- | :--- | :--- |
| `MailServiceUtil.sendEmail(mailMessage)` | Notification Template + Object Action | Extract email subject & body into Notification Template; configure `onAfterAdd` action. |
| `UserNotificationEventLocalServiceUtil` | User Notification Template | Select type `User Notification` in template; alerts appear in user's notification dockbar. |
| Hardcoded FreeMarker email templates | Liferay Notification Rich Text Editor | Migrate HTML/CSS to template with `[%FIELDNAME%]` tokens. |
| ModelListener sending email on record update | Object Action with trigger `onAfterUpdate` | Configure condition expression e.g. `status == 'CONFIRMED'`. |

---

## 4. Batch Client Extension Schema for Object Actions
Object Actions triggering notifications can be pre-packaged into the Batch Client Extension under `batch/01-03-[entity]-actions.batch-engine-data.json`:

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
      "description": "Send confirmation email to registrant after registration",
      "externalReferenceCode": "ACTION_[ENTITY]_SEND_CONFIRMATION",
      "label": {
        "en_US": "Send Confirmation Email"
      },
      "name": "sendConfirmationEmail",
      "objectActionExecutorKey": "notification",
      "objectActionTriggerKey": "onAfterAdd",
      "objectDefinitionExternalReferenceCode": "OBJ_[ENTITY]",
      "parameters": {
        "emailAddressField": "emailAddress",
        "emailTemplateKey": "[template-key]",
        "notificationType": "email"
      }
    }
  ]
}
```

---

## 5. Post-Migration Administrator Setup (Manual Checklist)
Because Notification Templates are often managed by business teams, Raphael outputs this guide in `MIGRATION.md`:

### Step 1: Create the Notification Template
1. In Liferay, navigate to **Control Panel $\rightarrow$ Notifications $\rightarrow$ Notification Templates**.
2. Click **(+) Add** $\rightarrow$ select **Email**.
3. **Name:** `[Entity Name] Confirmation Email`.
4. **Subject:** `Your [Entity Name] Registration is Confirmed!`.
5. **Body:** Design the message using tokens matching your object field names:
   ```html
   <p>Hello [%FIRSTNAME%] [%LASTNAME%],</p>
   <p>Thank you for registering. Your ticket type is <strong>[%TICKETTYPE%]</strong>.</p>
   <p>See you at the event!</p>
   ```
6. Click **Save**.

### Step 2: Wire the Template to the Object Action
1. Navigate to **Control Panel $\rightarrow$ Objects $\rightarrow$ [Your Object]**.
2. Open the **Actions** tab $\rightarrow$ click **(+) Add Action**.
3. **Basic Info:**
   - **Action Label:** `Send Confirmation Notification`
   - **Active:** Toggle to `Yes`
4. **Action Builder:**
   - **Trigger:** Select **On After Add** (runs when the Custom Element creates an entry).
   - **Action:** Select **Notification**.
   - **Notification Template:** Select the template created in Step 1.
   - **Recipient:** Select the object's recipient field (e.g. `emailAddress`).
5. Click **Save**.

*Result:* Every time an attendee completes the React Custom Element form, Liferay immediately and automatically dispatches the branded confirmation email!
