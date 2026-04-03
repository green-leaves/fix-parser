Build an IntelliJ plugin that allows users to parse FIX protocol messages. The plugin should:
- Accept one or more FIX messages (via clipboard or file)
- Parse messages into a structured table (Tag ID, Tag Name, Value)
- Smart detection of delimiter
- Display results in a modern Compose UI
- Allow saving parsed results to a file
- Allow split open in new side tab to compare
- Support both individual and batch processing

Technology Notes
- Use Artio Fix Parser
- Use Compose state management (mutableStateOf, remember)
- Use Jewel components: OutlinedTextField, Button, Table, ScrollableTable
- File operations via IntelliJ VirtualFile system
- Clipboard via ClipboardManager
- Use SOH (\u0001) as default FIX delimiter

Implementation Order

1. Create prototype and UI mock for user to review
2. Create data models and parser engine
3. Implement file read/write operations
4. Build basic UI: input area + parse button
5. Display results in table
6. Add file save functionality
7. Polish UI and error handling

Testing

- Copy-paste sample FIX messages
- Load file with multiple messages
- Parse and verify table displays correctly
- Save to file and verify output
- Test with malformed messages