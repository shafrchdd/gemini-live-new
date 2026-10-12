#!/usr/bin/env python3
"""Reject missing/mismatched theme colors that Kodular may initialize as empty text."""
import hashlib,json,re,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
COLORS={'PrimaryColor':'color.primary','PrimaryColorDark':'color.primary.dark','AccentColor':'color.accent'}
for name in ['GeminiFunctionDemo','GeminiBuddyFunctions','GeminiBuddyJsonFunctions']:
 path=ROOT/'examples'/(name+'.aia')
 assert hashlib.sha256(path.read_bytes()).hexdigest()==path.with_suffix('.aia.sha256').read_text().split()[0],name+' checksum mismatch'
 with zipfile.ZipFile(path) as z:
  assert z.testzip() is None
  props=dict(line.split('=',1) for line in z.read('youngandroidproject/project.properties').decode().splitlines() if '=' in line)
  scm=z.read('src/'+props['main'].replace('.','/')+'.scm').decode()
  form=json.loads(scm.split('$JSON\n',1)[1].rsplit('\n|#',1)[0])['Properties']
  assert form['Theme']==props['theme'],name+' theme mismatch'
  for screen_key,project_key in COLORS.items():
   value=form.get(screen_key,'')
   assert re.fullmatch(r'&H[0-9A-Fa-f]{8}',value),name+' invalid '+screen_key
   assert props.get(project_key)==value,name+' mismatched '+project_key
 print('PASS',name+': explicit matching theme and three valid ARGB colors')
