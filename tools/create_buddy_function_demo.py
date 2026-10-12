#!/usr/bin/env python3
"""Generate an automatic GeminiLive → DeskBuddy function-call demonstration."""
import argparse,hashlib,json,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from create_test_aia import ProjectBuilder,VERSIONS,YA_VERSION,LANGUAGE_VERSION,ROOT
class Demo(ProjectBuilder):
 def lookup(self,key):
  b=self.block('dictionaries_lookup');self.value(b,'KEY',self.literal(key));self.value(b,'DICT',self.global_variable('args'));self.value(b,'NOTFOUND',self.literal(''));return b
 def has(self,item,method):
  b=self.block('lists_is_in');self.value(b,'ITEM',item);self.value(b,'LIST',self.method('DeskBuddy1',method));return b
 def isdict(self):
  b=self.block('dictionaries_is_dict');self.value(b,'THING',self.global_variable('args'));return b
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
  button('ConnectButton','Register buddy functions and connect');button('DisconnectButton','Disconnect',False)
  label('StatusLabel','Disconnected')
  box('PromptInput','Make your face happy, then wave hello.');button('SendButton','Send prompt',False)
  button('EmotionButton','Test happy emotion',False);button('ReactionButton','Test wave reaction',False)
  label('CallLabel','No function call yet');label('ArgsLabel','');label('ResultLabel','');label('ResponseLabel','');label('ErrorLabel','')
  children.append(c('BatteryIconCheck','CheckBox',Text='Show battery symbol',Checked='False',TextColor='&HFF00E5FF'))
  children.append(c('WifiIconCheck','CheckBox',Text='Show Wi-Fi symbol',Checked='False',TextColor='&HFF00E5FF'))
  panel=c('MainPanel','VerticalArrangement',children=children,Width='-2')
  ext=c('GeminiLive1','GeminiLive',Voice='Puck',AudioEnabled='True',RawMessagesEnabled='False')
  buddy=c('DeskBuddy1','DeskBuddy',ShowBatteryIcon='False',ShowWifiIcon='False',Autonomous='False')
  web=c('Json1','Web')
  return '#|\n$JSON\n'+json.dumps({'authURL':['creator.kodular.io'],'YaVersion':YA_VERSION,'Source':'Form','Properties':{'$Name':'Screen1','$Type':'Form','$Version':'42','Uuid':'0','Title':'Gemini Buddy Functions','AppName':'Gemini Buddy Functions','Scrollable':'True','Sizing':'Responsive','Theme':'AppTheme.Light.DarkActionBar','PrimaryColor':'&HFF245A81','PrimaryColorDark':'&HFF153951','AccentColor':'&HFF007D77','BackgroundColor':'&HFF000000','ShowListsAsJson':'True','$Components':[panel,ext,buddy,web]}},separators=(',',':'))+'\n|#\n'
 def controls(self,on):return [self.enabled(n,on) for n in ['SendButton','EmotionButton','ReactionButton']]
 def blocks(self):
  for i,(n,val) in enumerate([('args',''),('value',''),('actionError',''),('result','')]):self.declare_global(n,self.literal(val),40+i*450)
  self.event('Screen1','Initialize',[self.method('DeskBuddy1','Attach',self.ref('BuddyPanel'))])
  registrations=[]
  for name,key,catalog,desc in [('buddy_set_emotion','emotion','emotions','Set the persistent avatar emotion to match the user request. Return success only after the application applies it.'),('buddy_play_reaction','reaction','reactions','Play a temporary avatar gesture for 3 seconds without replacing the base emotion. Use when the user requests an action.')]:
   schema={'type':'object','properties':{key:{'type':'string','enum':self.catalog[catalog]}},'required':[key]}
   registrations.append(self.method('GeminiLive1','AddFunction',self.literal(name),self.literal(desc),self.literal(json.dumps(schema,separators=(',',':')))))
  self.event('ConnectButton','Click',[self.conditional(self.compare(self.length(self.trim(self.prop('KeyInput','Text'))),'EQ',self.number(0)),[self.set_text('ErrorLabel','Enter your API key.')],[self.set_text('ErrorLabel',''),*self.controls(False),self.enabled('ConnectButton',False),self.enabled('DisconnectButton',True),self.method('GeminiLive1','ClearFunctions'),*registrations,self.prop('GeminiLive1','Model',self.trim(self.prop('ModelInput','Text'))),self.prop('GeminiLive1','SystemInstruction',self.literal('You are a cute cyan-eyed desk buddy. Use buddy_set_emotion for requested emotions and buddy_play_reaction for requested gestures. For requests with both, call both functions. Wait for actual function results before claiming success. Keep replies brief, in Malayalam or English matching the user. Do not speak tool names or JSON.')),self.set_text('StatusLabel','Connecting…'),self.method('GeminiLive1','Connect',self.trim(self.prop('KeyInput','Text')))])])
  self.event('DisconnectButton','Click',[self.method('GeminiLive1','Disconnect'),*self.controls(False),self.enabled('ConnectButton',True),self.enabled('DisconnectButton',False)])
  self.event('GeminiLive1','SetupComplete',[*self.controls(True),self.set_text('StatusLabel','Ready — emotion and reaction functions registered')])
  for button,prompt in [('SendButton',None),('EmotionButton','Call buddy_set_emotion with emotion happy. Tell me briefly whether it succeeded.'),('ReactionButton','Call buddy_play_reaction with reaction wave. Tell me briefly whether it started successfully.')]:
   self.event(button,'Click',[self.conditional(self.method('GeminiLive1','IsReady'),[self.set_text('ResponseLabel',''),self.method('GeminiLive1','SendText',self.prop('PromptInput','Text') if prompt is None else self.literal(prompt))],[self.set_text('ErrorLabel','Wait for SetupComplete.')])])
  def action(key,method,catalog):
   args=[self.global_variable('value')]+([self.number(3000)] if key=='reaction' else [])
   result=self.join(self.literal('{"ok":true,"'+key+'":'),self.method('Json1','JsonObjectEncode',self.global_variable('value')),self.literal(',"current_emotion":'),self.method('Json1','JsonObjectEncode',self.method('DeskBuddy1','CurrentEmotion')),self.literal(',"duration_ms":3000,"status":"started"}' if key=='reaction' else ',"status":"applied"}'))
   return [self.global_variable('value',self.lookup(key)),self.conditional(self.has(self.global_variable('value'),catalog),[self.method('DeskBuddy1',method,*args),self.conditional(self.compare(self.global_variable('actionError'),'EQ',self.literal('')),[self.global_variable('result',result)],[self.global_variable('result',self.literal('{"ok":false,"error":"DeskBuddy could not apply the action"}'))])],[self.global_variable('result',self.literal('{"ok":false,"error":"Missing or unsupported '+key+'"}'))])]
  dispatch=self.conditional(self.compare(self.parameter('name'),'EQ',self.literal('buddy_set_emotion')),action('emotion','SetEmotion','Emotions'),[self.conditional(self.compare(self.parameter('name'),'EQ',self.literal('buddy_play_reaction')),action('reaction','PlayReaction','Reactions'),[self.global_variable('result',self.literal('{"ok":false,"error":"Unknown function"}'))])])
  self.event('GeminiLive1','FunctionCall',[self.prop('CallLabel','Text',self.join(self.literal('Function: '),self.parameter('name'))),self.prop('ArgsLabel','Text',self.parameter('argumentsJson')),self.global_variable('actionError',self.literal('')),self.global_variable('result',self.literal('{"ok":false,"error":"Invalid arguments JSON object"}')),self.global_variable('args',self.literal('')),self.global_variable('args',self.method('Json1','JsonTextDecodeWithDictionaries',self.parameter('argumentsJson'))),self.conditional(self.isdict(),[dispatch]),self.prop('ResultLabel','Text',self.global_variable('result')),self.method('GeminiLive1','SendFunctionResult',self.parameter('callId'),self.parameter('name'),self.global_variable('result'))])
  self.event('DeskBuddy1','Error',[self.global_variable('actionError',self.parameter('message')),self.prop('ErrorLabel','Text',self.parameter('message'))])
  self.event('Screen1','ErrorOccurred',[self.global_variable('actionError',self.parameter('message')),self.prop('ErrorLabel','Text',self.join(self.parameter('functionName'),self.literal(': '),self.parameter('message')))])
  self.event('GeminiLive1','Error',[self.prop('ErrorLabel','Text',self.parameter('message')),self.prop('ConnectButton','Enabled',self.negate(self.method('GeminiLive1','IsConnected')))])
  self.event('GeminiLive1','Disconnected',[*self.controls(False),self.enabled('ConnectButton',True),self.enabled('DisconnectButton',False),self.prop('StatusLabel','Text',self.join(self.literal('Disconnected: '),self.parameter('reason')))])
  for event in ['TextReceived','OutputTranscription']:self.event('GeminiLive1',event,self.append_transcript('ResponseLabel','text'))
  for name,prop in [('BatteryIconCheck','ShowBatteryIcon'),('WifiIconCheck','ShowWifiIcon')]:self.event(name,'Changed',[self.prop('DeskBuddy1',prop,self.prop(name,'Checked'))])
  ET.SubElement(self.xml,'yacodeblocks',{'ya-version':YA_VERSION,'language-version':LANGUAGE_VERSION});ET.indent(self.xml,space='  ');return ET.tostring(self.xml,encoding='unicode')+'\n'
def main():
 p=argparse.ArgumentParser();p.add_argument('gemini_aix',type=Path);p.add_argument('buddy_aix',type=Path);a=p.parse_args();assets={};metadata=[]
 for path,pkg,kind in [(a.gemini_aix,'com.fabiyon.geminilive','GeminiLive'),(a.buddy_aix,'com.fabiyon.deskbuddy','DeskBuddy')]:
  with zipfile.ZipFile(path) as z:
   info=json.loads(z.read(pkg+'/components.json'))[0];metadata.append(info);VERSIONS[kind]=str(info['version'])
   for n in z.namelist():
    if not n.endswith('/'):
     if Path(n).is_absolute() or '..' in Path(n).parts:raise ValueError('Unsafe extension path')
     assets['assets/external_comps/'+n]=z.read(n)
 VERSIONS['Web']='6';b=Demo();b.catalog=json.loads((ROOT/'docs/DeskBuddy-catalog.json').read_text());package='appinventor.ai_shafrchdd.GeminiBuddyFunctions';source='src/'+package.replace('.','/')+'/Screen1'
 entries={source+'.scm':b.screen().encode(),source+'.bky':b.blocks().encode(),'youngandroidproject/project.properties':('\n'.join(['main='+package+'.Screen1','name=GeminiBuddyFunctions','assets=../assets','source=../src','build=../build','versioncode=1','versionname=1.0','useslocation=False','aname=Gemini Buddy Functions','androidminsdk=23','sizing=Responsive','showlistsasjson=True','theme=AppTheme.Light.DarkActionBar','color.primary=&HFF245A81','color.primary.dark=&HFF153951','color.accent=&HFF007D77',''])).encode()}
 for n,data in entries.items():
  target=ROOT/'examples/buddy-functions/project'/n;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
 entries.update(assets);output=ROOT/'examples/GeminiBuddyFunctions.aia'
 with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
  for n,data in sorted(entries.items()):
   info=zipfile.ZipInfo(n,(2026,10,12,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
 output.with_suffix('.aia.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n');print('Generated',output,len(b.types),'components',b.top_count,'events')
if __name__=='__main__':main()
