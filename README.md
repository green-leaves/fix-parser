# FIX Parser — IntelliJ Plugin

A lightweight [FIX protocol](https://www.fixtrading.org/) message parser built into your IDE. Paste a message or load a file and instantly see every field decoded — with tag names, values, and descriptions from the FIX dictionary.

All parsing happens locally. No data leaves your machine.

---

## Features

- **Paste or load** — paste raw FIX text directly or browse for a file
- **Auto-detect delimiters** — handles SOH (`\u0001`), pipe (`|`), tab, and more
- **Multi-message support** — automatically splits and parses concatenated messages
- **Summary table** — lists parsed messages with Time, Sender, Target, and Message Type
- **Filter bar** — filter the summary by raw message content; supports plain text, regex (`.*`), and negative/exclude (`!`) modes
- **Field detail view** — tag ID, name, value, and enum description for every field
- **Raw message view** — original text for quick copy/inspection
- **Copy support** — copy individual cells, rows, full table (TSV), or CSV from the detail view
- **FIX dictionary support** — FIX 4.0, 4.1, 4.2, 4.3, 4.4, 5.0, 5.0 SP1, 5.0 SP2, and custom XML dictionaries

---

## Usage

Open the **FIX Parser** tool window from the right-side panel.

1. Paste a FIX message into the input area (or click **Browse** to load a file)
2. The message is parsed automatically on paste
3. Click a row in the **Messages** table to see its fields in the detail panel
4. Use the filter bar to narrow down messages by content

### Filter bar

| Control | Behaviour |
|------------|-----------|
| Text input | Shows only messages whose raw content contains the text (case-insensitive) |
| `.*` toggle | Switches to regex matching |
| `!` toggle | Inverts the match — hides matching messages instead |
| `Esc` | Clears the filter |

---

## Building

```bash
./gradlew build
```

Run the plugin in a sandboxed IDE instance:

```bash
./gradlew runIde
```

Run unit tests:

```bash
./gradlew unitTest
```

---

## Project structure

```
src/
├── main/
│   ├── kotlin/io/hermes/fix/plugin/
│   │   ├── FixMessageParser.kt      # Pure parsing logic (delimiter detection, tag parsing)
│   │   ├── FixModels.kt             # Data classes: FixTag, FixMessage, ParsedResult
│   │   ├── FixParserToolMain.kt     # Tool window UI (summary table, filter bar, detail view)
│   │   └── LineNumberGutter.kt      # Line number gutter for the input area
│   └── resources/
│       ├── META-INF/plugin.xml
│       ├── icons/
│       └── spec/                    # Bundled FIX XML dictionaries
└── test/
    └── kotlin/io/hermes/fix/plugin/
        └── FixMessageParserTest.kt  # 30 unit tests for the parser
```

---

## Publishing

```bash
./gradlew publishPlugin
```

Requires a `PUBLISH_TOKEN` environment variable (JetBrains Marketplace token).
