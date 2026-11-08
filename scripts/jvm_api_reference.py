"""Generate a nonempty JVM signature reference from the exact release AAR."""
from pathlib import Path
import argparse
import hashlib
import html
import io
import json
import re
import subprocess
import tempfile
import zipfile


PUBLIC_BLOCK = re.compile(r'(?ms)^public\b[^\r\n]*\{.*?^\}')
PUBLIC_NAME = re.compile(r'\b(?:class|interface|enum|@interface)\s+([^\s<{]+)')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def build_reference(aar, module, version, output, javap):
    raw_aar = aar.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw_aar)) as archive:
        if archive.testzip() is not None or 'classes.jar' not in archive.namelist():
            raise ValueError('AAR lacks a valid classes.jar')
        raw_jar = archive.read('classes.jar')
    with zipfile.ZipFile(io.BytesIO(raw_jar)) as jar:
        if jar.testzip() is not None or len(jar.namelist()) != len(set(jar.namelist())):
            raise ValueError('Invalid or duplicate class archive entries')
        names = sorted(n[:-6].replace('/', '.') for n in jar.namelist()
                       if n.endswith('.class') and not n.endswith('/R.class')
                       and '/R$' not in n and not n.endswith('/package-info.class')
                       and n != 'module-info.class')
    if not names:
        raise ValueError('No class files in AAR')
    output.mkdir(parents=True, exist_ok=True)
    public = {}
    with tempfile.TemporaryDirectory(prefix='javap-', dir=output.parent) as tmp:
        jar_file = Path(tmp) / 'classes.jar'
        jar_file.write_bytes(raw_jar)
        for start in range(0, len(names), 20):
            result = subprocess.run([str(javap), '-public', '-classpath', str(jar_file),
                                     *names[start:start + 20]], capture_output=True,
                                    text=True, encoding='utf-8', errors='replace', timeout=180)
            if result.returncode:
                raise ValueError('javap failed for an AAR class batch')
            for block in PUBLIC_BLOCK.findall(result.stdout):
                first = block.splitlines()[0]
                match = PUBLIC_NAME.search(first)
                if not match or match.group(1) in public:
                    raise ValueError('Ambiguous or duplicate public declaration')
                public[match.group(1)] = block
    if not public:
        raise ValueError('No public JVM declarations found')
    title = f'{module} {version} JVM public signatures'
    body = '\n\n'.join(public[name] for name in sorted(public))
    markup = ('<!doctype html>\n<html lang="en"><head><meta charset="utf-8">'
              '<title>' + html.escape(title) + '</title></head><body><h1>' + html.escape(title) +
              '</h1><p>Generated from the exact release AAR using javap -public. '
              'JVM visibility may include Kotlin internal or synthetic declarations; '
              'this is a signature reference, not Kotlin KDoc or behavioral documentation.</p>'
              '<p>AAR SHA-256: <code>' + sha(raw_aar) + '</code></p><pre>' + html.escape(body) +
              '</pre></body></html>\n')
    # Bytes, not write_text(newline=...): that keyword needs Python 3.10, and macOS ships 3.9. The text
    # only contains '\n', so the bytes are identical to write_text(newline='\n') on every platform.
    (output / 'index.html').write_bytes(markup.encode('utf-8'))
    manifest = dict(schema_version=1, module=module, version=version, aar_sha256=sha(raw_aar),
                    classes_jar_sha256=sha(raw_jar), class_file_count=len(names),
                    public_class_count=len(public), public_classes=sorted(public),
                    signature_sha256=sha(body.encode('utf-8')),
                    scope='JVM public signatures; may include Kotlin internal or synthetic declarations; not KDoc')
    (output / 'api-manifest.json').write_bytes(
        (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--aar', type=Path, required=True)
    parser.add_argument('--module', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--javap', type=Path, required=True)
    args = parser.parse_args()
    if not args.aar.is_file() or not args.javap.is_file() or not args.module or not args.version:
        raise SystemExit('Missing AAR, javap, module or version')
    build_reference(args.aar, args.module, args.version, args.output, args.javap)
    print('Generated JVM API reference from exact release AAR:', args.module)


if __name__ == '__main__':
    main()
