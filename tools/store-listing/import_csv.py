"""Fills a Partner Center "Export listings" CSV with store/listing.json, for "Import listings".

  python tools/store-listing/import_csv.py --template exported.csv --screenshots <folder> --out <folder>

<screenshots> holds <language>/<file>.png from compose.py. The output folder gets listingData.csv
plus the screenshots at the same relative paths the CSV names; in Partner Center choose
Import listings and select that folder. Fields not set here (title, release notes, logos, ...)
keep the values from the export.
"""
import argparse
import csv
import io
import json
import os
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--template", required=True)
    parser.add_argument("--listing", default="store/listing.json")
    parser.add_argument("--screenshots", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    listings = json.load(open(args.listing, encoding="utf-8"))["listings"]
    with open(args.template, encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.reader(handle))
    header = rows[0]
    columns = {name: index for index, name in enumerate(header)}

    os.makedirs(args.out, exist_ok=True)
    for language, listing in listings.items():
        if language not in columns:
            raise SystemExit(f"The export has no '{language}' column; add the language in Partner Center first.")
        values = {
            "Description": listing["description"].replace("\n", "\r\n"),
            "ShortDescription": listing["shortDescription"],
        }
        for index in range(1, 21):
            features = listing["features"]
            values[f"Feature{index}"] = features[index - 1] if index <= len(features) else ""
        for index in range(1, 8):
            keywords = listing["keywords"]
            values[f"SearchTerm{index}"] = keywords[index - 1] if index <= len(keywords) else ""
        shots = listing["screenshots"]
        for index in range(1, 31):
            if index <= len(shots):
                shot = shots[index - 1]
                relative = f"{language}/{shot['file']}.png"
                source = os.path.join(args.screenshots, relative)
                if not os.path.exists(source):
                    raise SystemExit(f"Missing screenshot {source}")
                os.makedirs(os.path.join(args.out, language), exist_ok=True)
                shutil.copyfile(source, os.path.join(args.out, relative))
                values[f"DesktopScreenshot{index}"] = relative
                values[f"DesktopScreenshotCaption{index}"] = f"{shot['title']}. {shot['subtitle']}"
            else:
                # Clears the old screenshot (and any extra slots).
                values[f"DesktopScreenshot{index}"] = ""
                values[f"DesktopScreenshotCaption{index}"] = ""
        column = columns[language]
        for row in rows[1:]:
            if row and row[0] in values:
                row[column] = values[row[0]]

    buffer = io.StringIO()
    csv.writer(buffer, lineterminator="\r\n").writerows(rows)
    with open(os.path.join(args.out, "listingData.csv"), "w", encoding="utf-8-sig", newline="") as handle:
        handle.write(buffer.getvalue())
    print(os.path.join(args.out, "listingData.csv"))


if __name__ == "__main__":
    main()
