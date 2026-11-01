# Blueprint: Traditional Theme WAR & Widget Template Modernization

## 1. Executive Summary
Legacy Liferay 7.x front-ends relied heavily on monolithic **Theme WAR** packages (`portal_normal.ftl`, custom SASS stylesheets, color schemes) and **Widget Templates / ADTs** (`*.ftl` files with Java model calls). 

In modern Liferay (7.4+), this entire monolithic layer is transformed into a **modular, low-code, design-system-driven architecture**:
1. **`themeCSS` Client Extension**: Replaces custom Clay/Bootstrap styles without deploying WARs.
2. **Style Book Tokens (`frontend-token-definition.json`)**: Exposes brand colors, typography, and spacing to visual designers.
3. **Master Page Templates & Page Fragments**: Replaces hardcoded FreeMarker header/footer layouts with editable drag-and-drop building blocks.
4. **Collection Displays**: Replaces complex FreeMarker ADTs (Asset Publisher, Navigation Menu) with declarative visual collections.
5. **Specialized Asset CETs**: Replaces favicon and SVG icon spritemaps (`themeFavicon`, `themeSpritemap`).

---

## 2. Architectural Transformation Matrix

```
                        [ Legacy Monolithic Theme WAR ]
                                       │
     ┌──────────────────┬──────────────┼───────────────┬──────────────────┐
     ▼                  ▼              ▼               ▼                  ▼
[portal_normal.ftl]  [_clay_variables] [_custom.scss] [Color Schemes]   [Static Assets]
     │                  │              │               │                  │
     ▼                  ▼              ▼               ▼                  ▼
Master Page Template   Style Book    themeCSS Client   Style Books        themeFavicon &
+ Header/Footer        Tokens        Extension         (per scheme)       themeSpritemap
Page Fragments         (JSON)
```

| Legacy Theme File | Modern Target | Responsibility & Advantages |
| :--- | :--- | :--- |
| **`templates/portal_normal.ftl`** | **Master Page Template** | Defines the page frame with a central `<lfr-drop-zone>` for content pages. |
| **`templates/header.ftl` & `navigation.ftl`** | **Header & Navigation Fragment** | Reusable Page Fragment with editable logo (`data-lfr-editable-type="image"`) and site title. |
| **`templates/footer.ftl` & `social_media.ftl`**| **Footer Fragment** | Reusable Page Fragment with editable copyright text and social links. |
| **`css/_clay_variables.scss`** | **Style Book Tokens (`frontend-token-definition.json`)** | Exposes `$primary`, `$font-family-base`, and `$btn-border-radius` directly into Liferay Style Books. |
| **`css/clay.scss` & `_custom.scss`** | **`themeCSS` Client Extension** | Bundled `clay.css` and `main.css` deployed without server restarts. |
| **`liferay-look-and-feel.xml` `<color-scheme>`** | **Liferay Style Books** | Each color scheme (e.g., Default, Dark, Blue) becomes a selectable Style Book preset. |
| **`images/favicon.ico`** | **`themeFavicon` Client Extension** | Replaces favicon via simple YAML definition. |
| **`images/lexicon/icons.svg`** | **`themeSpritemap` Client Extension** | Replaces icon spritemap via simple YAML definition. |
| **`js/main.js`** | **`globalJS` CET** or **Fragment JS** | Global scripts become `globalJS`; navbar togglers are encapsulated in Header Fragment. |
| **`layouttpl/*.tpl` (Layout Templates)** | **Native Section Containers** | Retired. Content Pages provide responsive multi-column Grid & Flexbox containers visually. |

---

## 3. Widget Template (ADT) Modernization Architecture

Legacy Application Display Templates (`*.ftl`) required developers to call Java kernel APIs inside FreeMarker:
```freemarker
<#-- LEGACY ADT (ap-blog-presentation-template.ftl) -->
<#list entries as curEntry>
  <#assign assetRenderer = curEntry.getAssetRenderer() />
  <#assign blogEntry = assetRenderer.getAssetObject() />
  <h3>${htmlUtil.escape(assetRenderer.getTitle(locale))}</h3>
  <img src="${blogEntry.getCoverImageURL(themeDisplay)}" />
</#list>
```

### Modern Transformation:
1. **List / Grid Views (Asset Publisher)**:
   - Replaced by the native **Collection Display** widget connected to an Asset Collection or Object Collection.
   - The card layout is converted into a modern **Page Fragment**:
     ```html
     <div class="card h-100 shadow-sm">
       <img class="card-img-top" data-lfr-editable-id="cover-image" data-lfr-editable-type="image" src="" />
       <div class="card-body">
         <h3 class="card-title font-weight-bold" data-lfr-editable-id="title" data-lfr-editable-type="text">Post Title</h3>
         <p class="card-text text-muted small" data-lfr-editable-id="date" data-lfr-editable-type="text">Date</p>
         <p class="card-text" data-lfr-editable-id="summary" data-lfr-editable-type="rich-text">Summary</p>
       </div>
     </div>
     ```
   - In the Page Editor, marketers map `cover-image`, `title`, `date`, and `summary` directly to collection item fields visually.
2. **Single Content Views (Full Content)**:
   - Replaced by a **Display Page Template (DPT)** mapped to the target Content Type or Object.

---

## 4. End-to-End Delivery Structure

When a legacy theme module (`my-brand-theme/`) is migrated, Michelangelo outputs:
```
client-extensions/
├── [brand-slug]-theme-css/
│   ├── client-extension.yaml
│   ├── frontend-token-definition.json
│   └── static/css/
│       ├── clay.css
│       └── main.css
├── [brand-slug]-assets/
│   ├── client-extension.yaml
│   └── static/images/
│       ├── favicon.ico
│       └── icons.svg
fragments/
└── [brand-slug]-theme-fragments/
    ├── collection.json
    ├── header/
    │   ├── fragment.json
    │   ├── index.html
    │   └── index.css
    ├── footer/
    │   ├── fragment.json
    │   ├── index.html
    │   └── index.css
    └── content-card/
        ├── fragment.json
        ├── index.html
        └── index.css
```
