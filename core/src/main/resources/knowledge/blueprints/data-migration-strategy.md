# Blueprint: Service Builder Data to Liferay Objects Migration

## 1. Objective
Enable customers to seamlessly migrate existing records from legacy Service Builder database tables (`FOO_Entity`) into newly deployed Liferay Objects (`C_Entity`) without data loss, preserving foreign-key relationships and audit metadata.

---

## 2. Architecture & Pipeline Overview

The data migration consists of three phases:
```
[ Legacy Database / Service Builder Table ]
                  │
                  ▼
         [ Extractor Utility ]
   (SQL / JSON Export or Gogo Shell Script)
                  │
                  ▼
[ Batch Data Payload: 03-00-[entity]-entries.batch-engine-data.json ]
                  │
                  ▼
        [ Liferay Batch Engine ]
   (Deployed via Batch Client Extension or Headless REST)
                  │
                  ▼
        [ Liferay Object Entries ]
```

---

## 3. Data Extraction Contract

### 3.1 Field-by-Field Transformation Mapping

| Service Builder Column | Target Object Entry Attribute | Transformation Rule |
| :--- | :--- | :--- |
| `primaryKey` (e.g. `employeeId`) | `externalReferenceCode` | Use legacy PK prefixed with entity name (e.g. `EMP_10201`) to ensure uniqueness and idempotent `UPSERT`. |
| Audit: `createDate` / `modifiedDate`| Root properties or system dates | ISO 8601 format with UTC `Z` (e.g. `2024-03-27T10:00:00Z`). |
| Custom String / Numeric fields | Inside `"values": { ... }` block | Mapped directly by field name (e.g. `"fullName": "John Doe"`). |
| `Date` fields | Inside `"values": { ... }` block | Must be converted to ISO 8601 UTC string (`YYYY-MM-DDTHH:mm:ssZ`). |
| Foreign Key (e.g. `departmentId`) | Relationship ERC mapping | `"departmentRelationship": { "externalReferenceCode": "DEPT_201" }` |

---

## 4. Batch Engine Entry Payload Format

File naming: `batch/03-00-[entity-slug]-entries.batch-engine-data.json`

```json
{
  "configuration": {
    "className": "com.liferay.object.rest.dto.v1_0.ObjectEntry",
    "parameters": {
      "taskItemDelegateName": "C_Employee",
      "createStrategy": "UPSERT"
    }
  },
  "items": [
    {
      "externalReferenceCode": "EMP_1001",
      "values": {
        "fullName": "Alice Smith",
        "email": "alice.smith@example.com",
        "notes": "Senior Solutions Engineer",
        "salary": 115000.0,
        "hireDate": "2021-04-15T09:00:00Z",
        "active": true,
        "department": {
          "externalReferenceCode": "DEPT_10"
        }
      }
    },
    {
      "externalReferenceCode": "EMP_1002",
      "values": {
        "fullName": "Bob Johnson",
        "email": "bob.johnson@example.com",
        "notes": "Lead Architect",
        "salary": 130000.0,
        "hireDate": "2019-11-01T09:00:00Z",
        "active": true,
        "department": {
          "externalReferenceCode": "DEPT_10"
        }
      }
    }
  ]
}
```

---

## 5. Execution Options

1. **Option A: Bundled in the Batch Client Extension**
   - The developer places the data JSON in `batch/03-00-...batch-engine-data.json`.
   - When the client extension deploys, Liferay creates the folder, creates the object, creates the relationships, and imports the records in order.
2. **Option B: Headless Batch REST API**
   - Post directly to the Batch Engine API:
     `POST /o/headless-batch-engine/v1.0/import-task/com.liferay.object.rest.dto.v1_0.ObjectEntry`
   - Useful for large volumes (thousands of records) with progress tracking.
