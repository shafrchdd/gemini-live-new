#!/usr/bin/env python3
"""Build JSON utility and run pure implementation checks on a host JSON runtime."""
import subprocess,hashlib,urllib.request
from pathlib import Path
import build
ROOT=Path(__file__).resolve().parent.parent
java,javac=build.prepare()
subprocess.run(['python3',str(ROOT/'tools/build.py'),'--source',str(ROOT/'src/com/fabiyon/jsonutil/JsonUtil.java'),'--package','com.fabiyon.jsonutil','--build-dir',str(ROOT/'build/jsonutil')],check=True)
out=ROOT/'build/jsonutil/test-classes';out.mkdir(parents=True,exist_ok=True)
host=ROOT/'build/jsonutil/json-host.jar'
if not host.exists():
    with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20140107/json-20140107.jar') as response:host.write_bytes(response.read())
assert hashlib.sha256(host.read_bytes()).hexdigest()=='8e5aa0a368bee60347b5a4ad861d9f68c7793f60deeea89efd449eb70d5ae622','Host test dependency checksum mismatch'
cp=':'.join(str(x) for x in [ROOT/'build/jsonutil/classes',host,build.DEPS/'AndroidRuntime.jar',build.DEPS/'android.jar',build.DEPS/'kawa.jar'])
subprocess.run([str(javac),'-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(out),str(ROOT/'validation/JsonUtilTests.java')],check=True)
subprocess.run([str(java),'-cp',str(out)+':'+cp,'JsonUtilTests'],check=True)
