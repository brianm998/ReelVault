# ReelVault — Supported Interface Languages

The download page at https://brianm998.github.io/ReelVault/ auto-detects the
visitor's preferred language from their browser settings. A language selector
in the top-right corner of the page allows manual override; the choice is
persisted in `localStorage`.

Arabic uses right-to-left (RTL) layout automatically.

## Supported languages

| Code    | Language              | Native name           |
|---------|-----------------------|-----------------------|
| `en`    | English               | English               |
| `zh`    | Simplified Chinese    | 中文（简体）          |
| `es`    | Spanish               | Español               |
| `de`    | German                | Deutsch               |
| `ja`    | Japanese              | 日本語                |
| `fr`    | French                | Français              |
| `pt-br` | Brazilian Portuguese  | Português (Brasil)    |
| `ko`    | Korean                | 한국어                |
| `it`    | Italian               | Italiano              |
| `ru`    | Russian               | Русский               |
| `pl`    | Polish                | Polski                |
| `nl`    | Dutch                 | Nederlands            |
| `tr`    | Turkish               | Türkçe                |
| `ar`    | Arabic (RTL)          | العربية               |
| `hi`    | Hindi                 | हिन्दी               |
| `id`    | Indonesian            | Bahasa Indonesia      |
| `vi`    | Vietnamese            | Tiếng Việt            |
| `th`    | Thai                  | ภาษาไทย              |
| `uk`    | Ukrainian             | Українська            |
| `cs`    | Czech                 | Čeština               |

## Adding a new language

1. Add an entry to the `T` object in `docs/index.html`, keyed by the BCP 47
   language subtag (e.g. `"sv"` for Swedish).
2. Add an `<option>` to the `#lang-sel` `<select>` element in the same file.
3. Add detection logic in `detectLang()` if the language needs a special prefix
   rule (e.g. `zh-*` → `zh`, `pt-*` → `pt-br`).
4. Add a row to this table.
5. If the language is RTL, update the `dir` assignment in `setLang()`:
   change `(lang === 'ar')` to `(lang === 'ar' || lang === 'he')` etc.
