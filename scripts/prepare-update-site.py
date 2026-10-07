#!/usr/bin/env python3
"""Prepare GitHub Pages from a published p2 ZIP, without rebuilding the plugin."""
import argparse
import io
from pathlib import Path
import shutil
import zipfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('archive', type=Path)
parser.add_argument('destination', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
with zipfile.ZipFile(args.archive) as archive:
    for name in archive.namelist():
        path = Path(name)
        if path.is_absolute() or '..' in path.parts:
            raise ValueError(f'Invalid archive path: {name}')
    if archive.testzip() is not None:
        raise ValueError('Corrupt p2 archive')
    for name in ('p2.index', 'content.jar', 'artifacts.jar'):
        if name not in archive.namelist():
            raise ValueError(f'Missing p2 metadata: {name}')
    with zipfile.ZipFile(io.BytesIO(archive.read('content.jar'))) as metadata:
        content = ET.fromstring(metadata.read('content.xml'))
    versions = {unit.attrib['version'] for unit in content.findall('./units/unit')
                if unit.attrib['id'] == 'dev.edt.gitflow.feature.feature.group'}
    if len(versions) != 1:
        raise ValueError('Expected one Git Flow feature version')
    version = versions.pop()
    args.destination.mkdir(parents=True, exist_ok=True)
    archive.extractall(args.destination)
html = (root / 'website/index.html').read_text().replace('__VERSION__', version)
(args.destination / 'index.html').write_text(html)
shutil.copyfile(root / 'docs/images/git-flow-edt.jpg', args.destination / 'screenshot.jpg')
(args.destination / '.nojekyll').touch()
print(f'Prepared Git Flow {version}: {args.destination}')
