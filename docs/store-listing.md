# Microsoft Store listing (Pairly)

The listing text and screenshots live in the repository and are uploaded by a workflow, so you don't have to type them into Partner Center.

- **Text:** [`store/listing.json`](../store/listing.json). Each language (`en-us`, `de-de`) has a short description, description, features (up to 20), search terms (up to 7) and a headline for each screenshot.
- **Screenshots:** rendered by the Windows app's demo mode (`Pairly.Windows.exe --render-store-screenshots <folder>`), which uses invented content with no real phone. `tools/store-listing/compose.py` adds the headlines. Output is 1920×1080.

## Update the listing

1. Edit `store/listing.json` if the text should change.
2. **Actions → Store listing → Run workflow.**
   - It fails with a clear message while a release is still in certification. Run it again once that release is published.
   - Untick **Upload to Partner Center** to only render the screenshots (artifact **store-screenshots**).
3. Open Partner Center. The draft submission has the new text and screenshots for each language. Check them, then select **Submit for certification**. Or tick **Submit for certification** when running the workflow.

Submit the draft before the next release, so the release doesn't run into an open draft.

## Set once in Partner Center (not part of the workflow)

| Field | Value |
| --- | --- |
| Category | Productivity |
| Privacy policy URL | https://github.com/TheTobiTiger02/Pairly/blob/main/PRIVACY.md |
| Website / support | https://github.com/TheTobiTiger02/Pairly/issues |
| Age rating | The questionnaire in Partner Center (Pairly has no purchases and shares nothing publicly) |

Adding a new listing language also happens in Partner Center (**Store listings → Add/remove languages**). After that, add the same language to `store/listing.json` and run the workflow.
