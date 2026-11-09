#!/usr/bin/env python3
"""Build the Content Expiry XAR from src/.

Usage:
    python3 build.py            build dist/<artifact>-<version>.xar
    python3 build.py --check    build into a temp dir and validate only

No dependencies beyond the Python 3.11+ standard library.

Page sources live in src/pages/<Space>/<Sub>/<Page>.xml and use XWiki's own
page XML format (the same as a page exported from XWiki), with three
conveniences so the sources stay readable:

1. Header fields are optional. Anything missing (creator, author, version,
   syntax, hidden flag and so on) gets a sensible default.
2. Any element with a file="..." attribute takes its text from that file,
   relative to the page's XML file. Groovy and Velocity live in real .groovy
   and .vm files that editors can highlight.
3. An <object> may omit <name>, <number>, <guid> and <class>. The build fills
   them in. The class definition comes from src/classdefs/<Class>.xml or, for
   classes defined in this project, from the page that defines the class.
"""
import argparse
import copy
import json
import pathlib
import shutil
import sys
import tempfile
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT / "src"
PAGES = SRC / "pages"
CLASSDEFS = SRC / "classdefs"
DIST = ROOT / "dist"

GUID_NAMESPACE = uuid.UUID("6b1f6a52-2d1c-4f5e-9a59-0c1e7a1c0e57")

HEADER_ORDER = [
    "web", "name", "language", "defaultLanguage", "translation", "creator",
    "parent", "author", "contentAuthor", "version", "title", "comment",
    "minorEdit", "syntaxId", "hidden", "content",
]
DEFAULTS = {
    "language": "",
    "defaultLanguage": "",
    "translation": "0",
    "creator": "xwiki:XWiki.Admin",
    "author": "xwiki:XWiki.Admin",
    "contentAuthor": "xwiki:XWiki.Admin",
    "version": "1.1",
    "comment": "",
    "minorEdit": "false",
    "syntaxId": "xwiki/2.1",
    "hidden": "true",
    "content": "",
}

LICENCE_COMMENT = """
 * Content Expiry for XWiki
 * https://github.com/ChaoticOrb/xwiki-content-expiry
 * Released under the MIT License. See LICENSE in the repository.
"""


class BuildError(Exception):
    pass


def split_reference(reference):
    """Split a local document reference into (space, page).

    Handles XWiki's escaping: a backslash escapes the next character, so
    'A\\.B.C' is space 'A\\.B', page 'C'.
    """
    parts, current, i = [], "", 0
    while i < len(reference):
        ch = reference[i]
        if ch == "\\" and i + 1 < len(reference):
            current += reference[i:i + 2]
            i += 2
            continue
        if ch == ".":
            parts.append(current)
            current = ""
        else:
            current += ch
        i += 1
    parts.append(current)
    if len(parts) < 2:
        raise BuildError(f"reference '{reference}' needs at least a space and a page")
    return ".".join(parts[:-1]), parts[-1]


def expected_reference(path):
    rel = path.relative_to(PAGES).with_suffix("")
    return ".".join(rel.parts)


def load_sources():
    pages = {}
    for path in sorted(PAGES.rglob("*.xml")):
        try:
            tree = ET.parse(path)
        except ET.ParseError as e:
            raise BuildError(f"{path.relative_to(ROOT)}: not well-formed XML: {e}")
        root = tree.getroot()
        if root.tag != "xwikidoc":
            raise BuildError(f"{path.relative_to(ROOT)}: root element must be <xwikidoc>")
        ref = root.get("reference")
        if not ref:
            raise BuildError(f"{path.relative_to(ROOT)}: <xwikidoc> needs a reference attribute")
        if ref != expected_reference(path):
            raise BuildError(
                f"{path.relative_to(ROOT)}: reference '{ref}' does not match its path "
                f"(expected '{expected_reference(path)}')")
        pages[ref] = (path, root)
    if not pages:
        raise BuildError("no pages found under src/pages")
    return pages


def inline_files(element, base_dir, page_path):
    for el in element.iter():
        src = el.attrib.pop("file", None)
        if src is None:
            continue
        target = (base_dir / src).resolve()
        if not target.is_file():
            raise BuildError(f"{page_path.relative_to(ROOT)}: file '{src}' not found")
        el.text = target.read_text(encoding="utf-8")


def class_definitions(pages):
    defs = {}
    for path in sorted(CLASSDEFS.glob("*.xml")):
        el = ET.parse(path).getroot()
        if el.tag != "class":
            raise BuildError(f"{path.relative_to(ROOT)}: root element must be <class>")
        defs[path.stem] = el
    for ref, (_, root) in pages.items():
        cls = root.find("class")
        if cls is not None:
            name = cls.findtext("name")
            if name != ref:
                raise BuildError(f"{ref}: <class><name> must be '{ref}', found '{name}'")
            defs[ref] = cls
    return defs


def normalise_page(ref, root, defs):
    space, name = split_reference(ref)
    out = ET.Element("xwikidoc", {"version": "1.5", "reference": ref, "locale": ""})

    parent = "" if name == "WebHome" else f"{space}.WebHome"
    values = {"web": space, "name": name, "title": name, "parent": parent}
    values.update(DEFAULTS)
    for child in root:
        if child.tag in HEADER_ORDER:
            values[child.tag] = child.text or ""

    for tag in HEADER_ORDER:
        el = ET.SubElement(out, tag)
        el.text = values.get(tag, "") or None

    cls = root.find("class")
    if cls is not None:
        out.append(copy.deepcopy(cls))

    for att in root.findall("attachment"):
        out.append(att)

    counters = {}
    for obj in root.findall("object"):
        class_name = obj.findtext("className")
        if not class_name:
            raise BuildError(f"{ref}: every <object> needs a <className>")
        if class_name not in defs:
            raise BuildError(
                f"{ref}: no definition for class '{class_name}'. Add src/classdefs/{class_name}.xml "
                f"(tools/extract_classdef.py can copy one from an exported page).")
        number = obj.findtext("number")
        if number is None:
            number = str(counters.get(class_name, 0))
        counters[class_name] = int(number) + 1
        guid = obj.findtext("guid") or str(uuid.uuid5(GUID_NAMESPACE, f"{ref}|{class_name}|{number}"))

        new = ET.SubElement(out, "object")
        for tag, text in (("name", ref), ("number", number), ("className", class_name), ("guid", guid)):
            ET.SubElement(new, tag).text = text
        new.append(copy.deepcopy(obj.find("class") if obj.find("class") is not None else defs[class_name]))

        valid_props = {p.tag for p in defs[class_name] if p.find("classType") is not None}
        for prop in obj.findall("property"):
            for value in prop:
                if value.tag not in valid_props:
                    raise BuildError(
                        f"{ref}: object of {class_name} sets unknown property '{value.tag}'")
            new.append(prop)
    return out


def serialise(doc):
    ET.indent(doc, space="  ")
    body = ET.tostring(doc, encoding="unicode", short_empty_elements=True)
    return f'<?xml version="1.1" encoding="UTF-8"?>\n\n<!--{LICENCE_COMMENT}-->\n\n{body}\n'


def package_xml(meta, refs):
    pkg = ET.Element("package")
    infos = ET.SubElement(pkg, "infos")
    for tag, value in (
        ("name", meta["name"]),
        ("description", meta["description"]),
        ("licence", meta["licence"]),
        ("author", meta["author"]),
        ("version", meta["version"]),
        ("backupPack", "false"),
        ("preserveVersion", "false"),
        ("extensionId", meta["extensionId"]),
    ):
        ET.SubElement(infos, tag).text = value
    files = ET.SubElement(pkg, "files")
    for ref in refs:
        ET.SubElement(files, "file", {"defaultAction": "0", "language": ""}).text = ref
    ET.indent(pkg, space="  ")
    return '<?xml version="1.1" encoding="UTF-8"?>\n\n' + ET.tostring(pkg, encoding="unicode") + "\n"


def entry_path(ref):
    space, name = split_reference(ref)
    return "/".join(split_reference_parts(space) + [name]) + ".xml"


def split_reference_parts(space):
    parts, current, i = [], "", 0
    while i < len(space):
        if space[i] == "\\" and i + 1 < len(space):
            current += space[i + 1]
            i += 2
            continue
        if space[i] == ".":
            parts.append(current)
            current = ""
        else:
            current += space[i]
        i += 1
    parts.append(current)
    return parts


def validate(xar_path, refs):
    with zipfile.ZipFile(xar_path) as z:
        names = set(z.namelist())
        if "package.xml" not in names:
            raise BuildError("package.xml missing from XAR")
        listed = [f.text for f in ET.fromstring(z.read("package.xml")).iter("file")]
        if sorted(listed) != sorted(refs):
            raise BuildError("package.xml file list does not match the pages built")
        for ref in refs:
            entry = entry_path(ref)
            if entry not in names:
                raise BuildError(f"{entry} missing from XAR")
            doc = ET.fromstring(z.read(entry))
            if doc.get("reference") != ref:
                raise BuildError(f"{entry}: reference mismatch")
            for obj in doc.findall("object"):
                if obj.find("class") is None or not obj.findtext("guid"):
                    raise BuildError(f"{entry}: object missing class or guid")


def build(out_dir):
    meta = json.loads((SRC / "package.json").read_text(encoding="utf-8"))
    pages = load_sources()
    for ref, (path, root) in pages.items():
        inline_files(root, path.parent, path)
    defs = class_definitions(pages)

    xar_path = out_dir / f"{meta['artifact']}-{meta['version']}.xar"
    out_dir.mkdir(parents=True, exist_ok=True)
    refs = sorted(pages)
    with zipfile.ZipFile(xar_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("package.xml", package_xml(meta, refs))
        for ref in refs:
            doc = normalise_page(ref, pages[ref][1], defs)
            z.writestr(entry_path(ref), serialise(doc))
    validate(xar_path, refs)
    return xar_path, refs


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="build to a temp dir and validate only")
    args = parser.parse_args()
    try:
        if args.check:
            tmp = pathlib.Path(tempfile.mkdtemp())
            try:
                xar, refs = build(tmp)
            finally:
                shutil.rmtree(tmp, ignore_errors=True)
            print(f"OK: {len(refs)} pages valid")
        else:
            xar, refs = build(DIST)
            print(f"Built {xar.relative_to(ROOT)} with {len(refs)} pages")
    except BuildError as e:
        print(f"Build failed: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
