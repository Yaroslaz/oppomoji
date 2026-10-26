"""Prepare a pinned Android CBDT font and verify every Emoji 17.0 sequence.

No font binaries are part of the repository. The source is a third-party Apple
bitmap conversion on Noto's OpenType structure; this verifies coverage, not
Apple authorship of every individual image.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import urllib.request

import uharfbuzz as hb
from fontTools.ttLib import TTFont
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent


def sequences():
    rows = ROOT / "app/src/main/assets/emoji-17.0.txt"
    for row in rows.read_text(encoding="utf-8").splitlines():
        if not row or row.startswith("#"):
            continue
        codes, status, name = row.split(";", 2)
        yield "".join(chr(int(cp, 16)) for cp in codes.split()), status, name


def audit(path):
    blob = path.read_bytes()
    tt = TTFont(path, lazy=True)
    font = hb.Font(hb.Face(blob))
    hb.ot_font_set_funcs(font)
    glyph_order = tt.getGlyphOrder()
    bitmaps = tt["CBDT"].strikeData
    failures, shaped_ids = [], []
    verified_images = set()
    qualified = components = 0
    for text, status, name in sequences():
        qualified += status == "fully-qualified"
        components += status == "component"
        buffer = hb.Buffer()
        buffer.add_str(text)
        buffer.guess_segment_properties()
        hb.shape(font, buffer)
        glyphs = [g.codepoint for g in buffer.glyph_infos]
        if len(glyphs) != 1 or glyphs[0] == 0:
            failures.append({"text": text, "name": name, "glyphs": glyphs})
            continue
        shaped_ids.append(glyphs[0])
        glyph_name = glyph_order[glyphs[0]]
        entries = [(i, strike[glyph_name]) for i, strike in enumerate(bitmaps) if glyph_name in strike]
        if not entries:
            failures.append({"text": text, "name": name, "error": "No bitmap"})
        for index, entry in entries:
            key = (index, glyph_name)
            if key in verified_images:
                continue
            image = Image.open(io.BytesIO(entry.imageData)).convert("RGBA")
            if image.getchannel("A").getbbox() is None:
                failures.append({"text": text, "name": name, "error": "Empty bitmap"})
            verified_images.add(key)
    result = {"unicodeVersion": "17.0", "fullyQualified": qualified, "components": components,
              "total": qualified + components, "singleGlyph": len(shaped_ids),
              "verifiedBitmaps": len(verified_images), "failures": failures,
              "sha256": hashlib.sha256(blob).hexdigest(), "bytes": len(blob)}
    tt.close()
    return result, shaped_ids


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    source = json.loads((ROOT / "tools/emoji-set.json").read_text(encoding="utf-8"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    input_path = args.input or args.output.with_suffix(".source.ttf")
    if not args.input:
        with urllib.request.urlopen(source["url"], timeout=60) as response, input_path.open("wb") as output:
            while chunk := response.read(65536):
                output.write(chunk)
    digest = hashlib.sha256(input_path.read_bytes()).hexdigest()
    if input_path.stat().st_size != source["bytes"] or digest != source["sha256"]:
        raise ValueError("Source font does not match the pinned version")
    before, before_ids = audit(input_path)
    if before["failures"]:
        raise ValueError(f"Incomplete source font: {before['failures'][:5]}")
    tt = TTFont(input_path, lazy=True, recalcBBoxes=False, recalcTimestamp=False)
    preserve = {tag: hashlib.sha256(tt.getTableData(tag)).hexdigest() for tag in ("CBDT", "CBLC", "GSUB", "cmap", "hhea", "OS/2")}
    em = tt["head"].unitsPerEm
    tt["post"].underlinePosition = -round(em * .15)
    tt["post"].underlineThickness = round(em * .05)
    if "DSIG" in tt:
        del tt["DSIG"]
    tt.save(args.output, reorderTables=False)
    tt.close()
    result, after_ids = audit(args.output)
    if result["failures"] or before_ids != after_ids:
        raise ValueError("Preparation changed emoji coverage")
    check = TTFont(args.output, lazy=True)
    for tag, expected in preserve.items():
        if hashlib.sha256(check.getTableData(tag)).hexdigest() != expected:
            raise ValueError(f"Preparation unexpectedly changed {tag}")
    check.close()
    result["source"] = source
    result["underlinePositionPercent"] = 15
    result["underlineThicknessPercent"] = 5
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "source"}, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
