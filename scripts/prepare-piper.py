#!/usr/bin/env python3
"""Pinned, verified offline voice assets. Run before Gradle (also done by Actions)."""
import hashlib
import pathlib
import shutil
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / '.piper-cache'
ASSETS = ROOT / 'app/src/main/assets/piper'
VERSION = '1.13.8'
FILES = [
    ('sherpa-onnx-1.13.8.aar', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/', '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'),
    ('vits-piper-es_MX-claude-high-int8.tar.bz2', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/', '0f9fc9c07d2e17bdc0f5f33a657addae92085da85704cb86a861e59d32f3bbfa'),
    ('vits-piper-es_ES-davefx-medium-int8.tar.bz2', 'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/', '8bb8ac1cefb727caec9bd9c6c3185c673c8b42c53bd29bb25d5a7715dac37125'),
]

def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def main():
    CACHE.mkdir(exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    for name, base, sha in FILES:
        archive = CACHE / name
        if not archive.exists() or digest(archive) != sha:
            temporary = archive.with_suffix('.part')
            print('Downloading', name, flush=True)
            urllib.request.urlretrieve(base + name, temporary)
            if digest(temporary) != sha:
                temporary.unlink()
                raise RuntimeError('SHA-256 mismatch: ' + name)
            temporary.replace(archive)
        if name.endswith('.aar'):
            target = ROOT / 'app/libs' / name
            target.parent.mkdir(exist_ok=True)
            shutil.copyfile(archive, target)
        else:
            voice = 'claude' if 'claude' in name else 'davefx'
            target = ASSETS / voice
            if target.exists():
                shutil.rmtree(target)
            target.mkdir()
            with tarfile.open(archive) as tf:
                for member in tf.getmembers():
                    parts = pathlib.PurePosixPath(member.name).parts[1:]
                    if not parts or '..' in parts or member.issym() or member.islnk():
                        continue
                    dest = target.joinpath(*parts)
                    if member.isdir():
                        dest.mkdir(parents=True, exist_ok=True)
                    elif member.isfile():
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        with tf.extractfile(member) as src, dest.open('wb') as out:
                            shutil.copyfileobj(src, out)
            if not list(target.glob('*.onnx')) or not (target / 'tokens.txt').is_file():
                raise RuntimeError('Incomplete voice: ' + voice)
    print('Piper ready: Claude (MX), DaveFX (ES), int8, offline.')

if __name__ == '__main__':
    main()
