"""Overlay authored connector sources onto the existing auth-free vendor bundle.

Never pack a phone runtime directory: that would contain credentials.
"""
from pathlib import Path
import zipfile

source = Path(__file__).resolve().parent
asset = source.parent / 'app/src/main/assets/phone-runtime.zip'
with zipfile.ZipFile(asset) as archive:
    entries = {name: archive.read(name) for name in archive.namelist() if not name.endswith('/')}
assert not any('/auth/' in name or name.endswith(('creds.json', 'rpc-config.json')) for name in entries)
vendor = 'node_modules/baileys/lib/Utils/messages-media.js'
text = entries[vendor].decode()
if 'signal: options.signal' not in text:
    old = "dispatcher: options.dispatcher,\n        method: 'GET',"
    assert old in text, 'Vendor fetch implementation changed; review timeout patch'
    text = text.replace(old, "dispatcher: options.dispatcher,\n        signal: options.signal,\n        method: 'GET',", 1)
if "fetched.once('error', error => output.destroy(error))" not in text:
    old = '    return fetched.pipe(output, { end: true });'
    assert old in text, 'Vendor stream implementation changed; review error patch'
    text = text.replace(old, "    fetched.once('error', error => output.destroy(error));\n    output.once('close', () => fetched.destroy());\n" + old, 1)
entries[vendor] = text.encode()
for file in source.glob('*.mjs'):
    if not file.name.endswith('.test.mjs'):
        entries[file.name] = file.read_bytes()
temporary = asset.with_suffix('.tmp')
with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, content in sorted(entries.items()):
        archive.writestr(name, content)
temporary.replace(asset)
print('Built auth-free runtime with request cancellation and stream error propagation.')

# An isolated auth-free copy lets desktop tests exercise the actual packaged dependencies.
verification = source.parent / 'app/build/runtime-verification'
verification.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(asset) as archive:
    archive.extractall(verification)
