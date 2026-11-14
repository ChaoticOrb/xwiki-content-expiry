#!/usr/bin/env python3
"""Copy an XClass definition out of an exported XWiki page into src/classdefs/.

Usage: tools/extract_classdef.py <exported-page.xml> <ClassName> [<ClassName> ...]

Objects in our page sources can omit their <class> block; the build injects it
from src/classdefs/<ClassName>.xml. This tool fills that folder from any page
exported from XWiki (or from XWiki's own source) that has an object of the class.
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

OUT = pathlib.Path(__file__).resolve().parent.parent / "src" / "classdefs"


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    root = ET.parse(argv[1]).getroot()
    for wanted in argv[2:]:
        found = None
        own = root.find("class")
        if own is not None and own.findtext("name") == wanted:
            found = own  # the page defines this class itself
        for obj in root.iter("object"):
            if found is not None:
                break
            if obj.findtext("className") == wanted and obj.find("class") is not None:
                found = obj.find("class")
                break
        if found is None:
            print(f"{wanted}: no object with a class definition in {argv[1]}")
            continue
        found.tail = "\n"
        ET.indent(found, space="  ")
        OUT.mkdir(parents=True, exist_ok=True)
        target = OUT / f"{wanted}.xml"
        target.write_text(ET.tostring(found, encoding="unicode") , encoding="utf-8")
        print(f"wrote {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
