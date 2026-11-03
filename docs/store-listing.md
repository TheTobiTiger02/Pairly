# Microsoft Store listing (Pairly)

The listing text and screenshots live in the repository and are uploaded by a workflow, so you don't have to type them into Partner Center.

- **Text:** [`store/listing.json`](../store/listing.json). Each language (`en-us`, `de-de`) has a short description, description, features (up to 20), search terms (up to 7) and a headline for each screenshot.
- **Screenshots:** rendered by the Windows app's demo mode (`Pairly.Windows.exe --render-store-screenshots <folder>`), which uses invented content with no real phone. `tools/store-listing/compose.py` adds the headlines. Output is 1920×1080.

## Update the listing (Import listings, reliable)

Partner Center didn't keep listing changes made through the submission API for Pairly, so use its own import:

1. Edit `store/listing.json` if needed, then run **Actions → Store listing** with **Upload to Partner Center** unticked.
2. Download the run's **store-listing-import** artifact and unzip it. It holds `listingData.csv` and the screenshots.
3. In Partner Center, open a draft submission (**Update** if there is none) → **Store listings → Import listings**, and select the unzipped folder.
4. Check **Store listings → English** and **German**, then **Submit for certification**.

`store/listing-template.csv` is an export from Partner Center (**Export listings**). Replace it with a fresh export if Partner Center adds fields.

## Update the listing (API upload)

1. Edit `store/listing.json` if the text should change.
2. **Actions → Store listing → Run workflow.**
   - It fails with a clear message while a release is still in certification. Run it again once that release is published.
   - Untick **Upload to Partner Center** to only render the screenshots (artifact **store-screenshots**).
3. Open Partner Center. The draft submission has the new text for each language. Check it, then select **Submit for certification**, or tick **Submit for certification** when running the workflow.
   - Partner Center processes the new screenshots only when the draft is submitted, so until then the draft still shows the old ones.
   - To see exactly what the draft holds (text, features, search terms, and each screenshot with its status), run the workflow with **Only show what Partner Center holds now**. It changes nothing.

Submit the draft before the next release, so the release doesn't run into an open draft.

## Set once in Partner Center (not part of the workflow)

| Field | Value |
| --- | --- |
| Category | Productivity |
| Privacy policy URL | https://github.com/TheTobiTiger02/Pairly/blob/main/PRIVACY.md |
| Website / support | https://github.com/TheTobiTiger02/Pairly/issues |
| Age rating | The questionnaire in Partner Center (Pairly has no purchases and shares nothing publicly) |

Adding a new listing language also happens in Partner Center (**Store listings → Add/remove languages**). After that, add the same language to `store/listing.json` and run the workflow.
