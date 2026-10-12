#!/usr/bin/env python3
"""Patch a supplied demo AIA to use editable instructions, without embedding API keys."""
import argparse,hashlib,json,zipfile,xml.etree.ElementTree as E
from pathlib import Path
from create_test_aia import ROOT
p=argparse.ArgumentParser();p.add_argument('aia',type=Path);p.add_argument('--output',type=Path,default=ROOT/'examples/GeminiBuddyJsonFunctionsCustom.aia');a=p.parse_args()
with zipfile.ZipFile(a.aia) as z:
 entries={n:z.read(n) for n in z.namelist() if not n.endswith('/')}
scm_name=next(n for n in entries if n.endswith('Screen1.scm'));bky_name=scm_name[:-4]+'.bky';raw=entries[scm_name].decode();screen=json.loads(raw.split('$JSON\n',1)[1].rsplit('\n|#',1)[0]);xml=E.fromstring(entries[bky_name]);ns=xml.tag.split('}')[0]+'}' if '}' in xml.tag else '';E.register_namespace('',ns[1:-1])
def tag(n):return ns+n
def children(b,n):return [x for x in b if x.tag==tag(n)]
def all_components(c):
 yield c
 for x in c.get('$Components',[]):yield from all_components(x)
components={c['$Name']:c for c in all_components(screen['Properties'])};setters=[];custom=''
for b in xml.iter(tag('block')):
 m=next(iter(children(b,'mutation')),None)
 if m is not None and m.get('property_name')=='SystemInstruction' and m.get('set_or_get')=='set':
  setters.append(b)
  values=children(b,'value');child=children(values[0],'block')[0] if values else None
  if child is not None and child.get('type')=='text':
   text=next(iter(children(child,'field')),None);value=text.text or '' if text is not None else ''
   if value and not value.startswith('You are a warm, playful desk companion'):custom=value
if not setters:raise ValueError('No SystemInstruction setter found')
if not custom:custom=(ROOT/'docs/DeskBuddy-natural-system-instruction.txt').read_text()
# Remove the stored API key before making a reviewable/publishable artifact.
if 'KeyInput' in components:components['KeyInput']['Text']=''
field='SystemInstructionInput'
if field not in components:
 panel=components['MainPanel']['$Components'];i=next(i for i,c in enumerate(panel) if c['$Name']=='ConnectButton')
 panel[i:i]=[{'$Name':'SystemInstructionLabel','$Type':'Label','$Version':'10','Uuid':'-91001','Text':'System instruction — edit before connecting; reconnect after changes','Width':'-2','TextColor':'&HFF00E5FF'},{'$Name':field,'$Type':'TextBox','$Version':'13','Uuid':'-91002','Text':custom,'MultiLine':'True','Width':'-2','Height':'180'}]
for i,b in enumerate(setters):
 value=next(x for x in children(b,'value') if x.get('name')=='VALUE');value.clear();value.set('name','VALUE')
 getter=E.SubElement(value,tag('block'),{'type':'component_set_get','id':'custom_instruction_get_'+str(i)})
 E.SubElement(getter,tag('mutation'),{'component_type':'TextBox','instance_name':field,'property_name':'Text','set_or_get':'get','is_generic':'false'})
 E.SubElement(getter,tag('field'),{'name':'COMPONENT_SELECTOR'}).text=field;E.SubElement(getter,tag('field'),{'name':'PROP'}).text='Text'
entries[scm_name]=('#|\n$JSON\n'+json.dumps(screen,ensure_ascii=False,separators=(',',':'))+'\n|#\n').encode();entries[bky_name]=E.tostring(xml,encoding='utf-8')
a.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED) as z:
 for n,data in sorted(entries.items()):
  if Path(n).is_absolute() or '..' in Path(n).parts:raise ValueError('Unsafe archive path')
  item=zipfile.ZipInfo(n,(2026,10,12,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED;z.writestr(item,data)
a.output.with_suffix('.aia.sha256').write_text(hashlib.sha256(a.output.read_bytes()).hexdigest()+'  '+a.output.name+'\n')
print('Patched',len(setters),'instruction setters; preserved uploaded assets/blocks; cleared API key; output:',a.output)
