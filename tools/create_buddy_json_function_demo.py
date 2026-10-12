#!/usr/bin/env python3
"""Generate GeminiLive → DeskBuddy functions using the JsonUtil extension."""
import argparse,hashlib,json,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from create_test_aia import ProjectBuilder,VERSIONS,YA_VERSION,LANGUAGE_VERSION,ROOT
class Demo(ProjectBuilder):
 def lookup(self,key):
  return self.method('JsonUtil1','GetValue',self.global_variable('args'),self.literal(key),self.literal(''))
 def has(self,item,method):
  b=self.block('lists_is_in');self.value(b,'ITEM',item);self.value(b,'LIST',self.method('DeskBuddy1',method));return b
 def isdict(self):
  return self.compare(self.method('JsonUtil1','ValueType',self.global_variable('args'),self.literal('__root_check__')),'NEQ',self.literal('error'))
 def build_result(self,ok,error=None,key=None):
  result=self.method('JsonUtil1','SetBoolean',self.literal('{}'),self.literal('ok'),self.boolean(ok))
  if error is not None:return self.method('JsonUtil1','SetText',result,self.literal('error'),self.literal(error))
  result=self.method('JsonUtil1','SetText',result,self.literal(key),self.global_variable('value'))
  result=self.method('JsonUtil1','SetText',result,self.literal('current_emotion'),self.method('DeskBuddy1','CurrentEmotion'))
  if key=='reaction':result=self.method('JsonUtil1','SetNumber',result,self.literal('duration_ms'),self.number(3000))
  return self.method('JsonUtil1','SetText',result,self.literal('status'),self.literal('started' if key=='reaction' else 'applied'))
 def ref(self,name):
  b=self.block('component_component_block');self.mutation(b,component_type=self.types[name],instance_name=name);self.field(b,'COMPONENT_SELECTOR',name);return b
 def screen(self):
  c=self.component;children=[]
  def label(n,t):children.append(c(n,'Label',Text=t,TextColor='&HFF00E5FF',Width='-2'))
  def box(n,t):children.append(c(n,'TextBox',Text=t,Width='-2'))
  def button(n,t,on=True):children.append(c(n,'Button',Text=t,Enabled=str(on),Width='-2'))
  label('HelpLabel','Enter API key, connect, then try an emotion or reaction. Function calls control the avatar and return results automatically.')
  children.append(c('BuddyPanel','VerticalArrangement',Height='300',Width='-2',BackgroundColor='&HFF000000'))
  label('KeyLabel','Gemini API key (not saved)');box('KeyInput','')
  label('ModelLabel','Live model ID');box('ModelInput','gemini-3.8-live')
  label('SystemInstructionLabel','System instruction — edit before connecting; reconnect after changes')
  children.append(c('SystemInstructionInput','TextBox',Text=(ROOT/'docs/DeskBuddy-natural-system-instruction.txt').read_text(),MultiLine='True',Width='-2',Height='180'))
  button('ConnectButton','Register buddy functions and connect');button('DisconnectButton','Disconnect',False)
  label('StatusLabel','Disconnected')
  box('PromptInput','Hi! I had a great day today. How are you?');button('SendButton','Send prompt',False)
  button('EmotionButton','Try good news',False);button('ReactionButton','Try greeting',False)
  label('CallLabel','No function call yet');label('ArgsLabel','');label('ResultLabel','');label('ResponseLabel','');label('ErrorLabel','')
  children.append(c('BatteryIconCheck','CheckBox',Text='Show battery symbol',Checked='False',TextColor='&HFF00E5FF'))
  children.append(c('WifiIconCheck','CheckBox',Text='Show Wi-Fi symbol',Checked='False',TextColor='&HFF00E5FF'))
  panel=c('MainPanel','VerticalArrangement',children=children,Width='-2')
  ext=c('GeminiLive1','GeminiLive',Voice='Puck',AudioEnabled='True',RawMessagesEnabled='False')
  buddy=c('DeskBuddy1','DeskBuddy',ShowBatteryIcon='False',ShowWifiIcon='False',Autonomous='False')
  web=c('JsonUtil1','JsonUtil')
  return '#|\n$JSON\n'+json.dumps({'authURL':['creator.kodular.io'],'YaVersion':YA_VERSION,'Source':'Form','Properties':{'$Name':'Screen1','$Type':'Form','$Version':'42','Uuid':'0','Title':'Gemini Buddy JSON Functions','AppName':'Gemini Buddy JSON Functions','Scrollable':'True','Sizing':'Responsive','Theme':'AppTheme.Light.DarkActionBar','PrimaryColor':'&HFF245A81','PrimaryColorDark':'&HFF153951','AccentColor':'&HFF007D77','BackgroundColor':'&HFF000000','ShowListsAsJson':'True','$Components':[panel,ext,buddy,web]}},separators=(',',':'))+'\n|#\n'
 def controls(self,on):return [self.enabled(n,on) for n in ['SendButton','EmotionButton','ReactionButton']]
 def blocks(self):
  for i,(n,val) in enumerate([('args',''),('value',''),('actionError',''),('result','')]):self.declare_global(n,self.literal(val),40+i*450)
  self.event('Screen1','Initialize',[self.method('DeskBuddy1','Attach',self.ref('BuddyPanel'))])
  registrations=[]
  for name,key,catalog,desc in [('buddy_set_emotion','emotion','emotions','Silently express the conversational mood through the avatar. Use naturally when the mood changes, without waiting for an explicit user request. Never announce the face change or its result.'),('buddy_play_reaction','reaction','reactions','Silently add an occasional fitting gesture to conversation, such as a greeting wave or celebration. Never narrate the gesture or acknowledge its execution result.')]:
   schema={'type':'object','properties':{key:{'type':'string','enum':self.catalog[catalog]}},'required':[key]}
   registrations.append(self.method('GeminiLive1','AddFunction',self.literal(name),self.literal(desc),self.literal(json.dumps(schema,separators=(',',':')))))
  self.event('ConnectButton','Click',[self.conditional(self.compare(self.length(self.trim(self.prop('KeyInput','Text'))),'EQ',self.number(0)),[self.set_text('ErrorLabel','Enter your API key.')],[self.set_text('ErrorLabel',''),*self.controls(False),self.enabled('ConnectButton',False),self.enabled('DisconnectButton',True),self.method('GeminiLive1','ClearFunctions'),*registrations,self.prop('GeminiLive1','Model',self.trim(self.prop('ModelInput','Text'))),self.prop('GeminiLive1','SystemInstruction',self.prop('SystemInstructionInput','Text')),self.set_text('StatusLabel','Connecting…'),self.method('GeminiLive1','Connect',self.trim(self.prop('KeyInput','Text')))])])
  self.event('DisconnectButton','Click',[self.method('GeminiLive1','Disconnect'),*self.controls(False),self.enabled('ConnectButton',True),self.enabled('DisconnectButton',False)])
  self.event('GeminiLive1','SetupComplete',[*self.controls(True),self.set_text('StatusLabel','Ready — emotion and reaction functions registered')])
  for button,prompt in [('SendButton',None),('EmotionButton','I have some good news: I passed my exam!'),('ReactionButton','Hey buddy! Nice to meet you.')]:
   self.event(button,'Click',[self.conditional(self.method('GeminiLive1','IsReady'),[self.set_text('ResponseLabel',''),self.method('GeminiLive1','SendText',self.prop('PromptInput','Text') if prompt is None else self.literal(prompt))],[self.set_text('ErrorLabel','Wait for SetupComplete.')])])
  def action(key,method,catalog):
   args=[self.global_variable('value')]+([self.number(3000)] if key=='reaction' else [])
   result=self.build_result(True,key=key)
   return [self.global_variable('value',self.lookup(key)),self.conditional(self.has(self.global_variable('value'),catalog),[self.method('DeskBuddy1',method,*args),self.conditional(self.compare(self.global_variable('actionError'),'EQ',self.literal('')),[self.global_variable('result',result)],[self.global_variable('result',self.build_result(False,'DeskBuddy could not apply the action'))])],[self.global_variable('result',self.build_result(False,'Missing or unsupported '+key))])]
  dispatch=self.conditional(self.compare(self.parameter('name'),'EQ',self.literal('buddy_set_emotion')),action('emotion','SetEmotion','Emotions'),[self.conditional(self.compare(self.parameter('name'),'EQ',self.literal('buddy_play_reaction')),action('reaction','PlayReaction','Reactions'),[self.global_variable('result',self.build_result(False,'Unknown function'))])])
  self.event('GeminiLive1','FunctionCall',[self.prop('CallLabel','Text',self.join(self.literal('Function: '),self.parameter('name'))),self.prop('ArgsLabel','Text',self.parameter('argumentsJson')),self.global_variable('actionError',self.literal('')),self.global_variable('result',self.build_result(False,'Invalid arguments JSON object')),self.global_variable('args',self.parameter('argumentsJson')),self.conditional(self.method('JsonUtil1','IsValid',self.global_variable('args')),[self.conditional(self.isdict(),[dispatch])]),self.prop('ResultLabel','Text',self.global_variable('result')),self.method('GeminiLive1','SendFunctionResult',self.parameter('callId'),self.parameter('name'),self.global_variable('result'))])
  self.event('JsonUtil1','Error',[self.global_variable('actionError',self.parameter('message')),self.prop('ErrorLabel','Text',self.join(self.parameter('operation'),self.literal(': '),self.parameter('message')))])
  self.event('DeskBuddy1','Error',[self.global_variable('actionError',self.parameter('message')),self.prop('ErrorLabel','Text',self.parameter('message'))])
  self.event('Screen1','ErrorOccurred',[self.global_variable('actionError',self.parameter('message')),self.prop('ErrorLabel','Text',self.join(self.parameter('functionName'),self.literal(': '),self.parameter('message')))])
  self.event('GeminiLive1','Error',[self.prop('ErrorLabel','Text',self.parameter('message')),self.prop('ConnectButton','Enabled',self.negate(self.method('GeminiLive1','IsConnected')))])
  self.event('GeminiLive1','Disconnected',[*self.controls(False),self.enabled('ConnectButton',True),self.enabled('DisconnectButton',False),self.prop('StatusLabel','Text',self.join(self.literal('Disconnected: '),self.parameter('reason')))])
  for event in ['TextReceived','OutputTranscription']:self.event('GeminiLive1',event,self.append_transcript('ResponseLabel','text'))
  for name,prop in [('BatteryIconCheck','ShowBatteryIcon'),('WifiIconCheck','ShowWifiIcon')]:self.event(name,'Changed',[self.prop('DeskBuddy1',prop,self.prop(name,'Checked'))])
  ET.SubElement(self.xml,'yacodeblocks',{'ya-version':YA_VERSION,'language-version':LANGUAGE_VERSION});ET.indent(self.xml,space='  ');return ET.tostring(self.xml,encoding='unicode')+'\n'
def main():
 p=argparse.ArgumentParser();p.add_argument('gemini_aix',type=Path);p.add_argument('buddy_aix',type=Path);p.add_argument('--json-aix',type=Path,default=ROOT/'dist/com.fabiyon.jsonutil.aix');a=p.parse_args();assets={};metadata=[]
 for path,pkg,kind in [(a.gemini_aix,'com.fabiyon.geminilive','GeminiLive'),(a.buddy_aix,'com.fabiyon.deskbuddy','DeskBuddy'),(a.json_aix,'com.fabiyon.jsonutil','JsonUtil')]:
  with zipfile.ZipFile(path) as z:
   info=json.loads(z.read(pkg+'/components.json'))[0];metadata.append(info);VERSIONS[kind]=str(info['version'])
   for n in z.namelist():
    if not n.endswith('/'):
     if Path(n).is_absolute() or '..' in Path(n).parts:raise ValueError('Unsafe extension path')
     assets['assets/external_comps/'+n]=z.read(n)
 b=Demo();b.catalog=json.loads((ROOT/'docs/DeskBuddy-catalog.json').read_text());package='appinventor.ai_shafrchdd.GeminiBuddyJsonFunctions';source='src/'+package.replace('.','/')+'/Screen1'
 entries={source+'.scm':b.screen().encode(),source+'.bky':b.blocks().encode(),'youngandroidproject/project.properties':('\n'.join(['main='+package+'.Screen1','name=GeminiBuddyJsonFunctions','assets=../assets','source=../src','build=../build','versioncode=1','versionname=1.0','useslocation=False','aname=Gemini Buddy JSON Functions','androidminsdk=23','sizing=Responsive','showlistsasjson=True','theme=AppTheme.Light.DarkActionBar','color.primary=&HFF245A81','color.primary.dark=&HFF153951','color.accent=&HFF007D77',''])).encode()}
 for n,data in entries.items():
  target=ROOT/'examples/buddy-json-functions/project'/n;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
 entries.update(assets);output=ROOT/'examples/GeminiBuddyJsonFunctions.aia'
 with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
  for n,data in sorted(entries.items()):
   info=zipfile.ZipInfo(n,(2026,10,12,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
 output.with_suffix('.aia.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n');print('Generated',output,len(b.types),'components',b.top_count,'events')
if __name__=='__main__':main()
