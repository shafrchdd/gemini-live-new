#!/usr/bin/env python3
"""Build a minimal function-call AIA from a supplied GeminiLive AIX."""
import argparse,json,hashlib,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from create_test_aia import ProjectBuilder,VERSIONS,YA_VERSION,LANGUAGE_VERSION,ROOT
class Demo(ProjectBuilder):
 def screen(self):
  c=self.component
  children=[]
  def label(n,t):children.append(c(n,'Label',Text=t,Width='-2'))
  def box(n,t):children.append(c(n,'TextBox',Text=t,Width='-2'))
  def button(n,t,on=True):children.append(c(n,'Button',Text=t,Enabled=str(on),Width='-2'))
  label('HelpLabel','1. Enter API key. 2. Connect (registers function). 3. Request function. 4. Inspect arguments and Return result. Edit function inputs before connecting; reconnect after changes.')
  label('KeyLabel','Gemini API key (not saved)');box('KeyInput','')
  label('ModelLabel','Live model ID');box('ModelInput','gemini-3.8-live')
  label('NameLabel','Function name');box('NameInput','get_test_status')
  label('DescriptionLabel','Function description');box('DescriptionInput','Get the current test status of this app. Call this when asked to check app status.')
  label('SchemaLabel','Parameters JSON Schema');box('SchemaInput',json.dumps({'type':'object','properties':{'request':{'type':'string','description':'What status the user wants to check.'}},'required':['request']}))
  button('ConnectButton','Register function and connect');button('DisconnectButton','Disconnect',False)
  label('StatusLabel','Disconnected');box('PromptInput','Call get_test_status with request equal to function-call-test. Wait for its result, then tell me the returned status.')
  button('AskButton','Request function / send prompt',False)
  label('CallLabel','No function call yet');label('ArgsLabel','');label('IdLabel','')
  label('ResultLabel','Function result JSON');box('ResultInput','{"ok":true,"status":"Function calling works","app":"GeminiFunctionDemo"}')
  button('ReplyButton','Return function result',False);label('ResponseLabel','');label('ErrorLabel','')
  panel=c('MainPanel','VerticalArrangement',children=children,Width='-2')
  ext=c('GeminiLive1','GeminiLive',AudioEnabled='True',Voice='Puck',RawMessagesEnabled='False')
  screen={'$Name':'Screen1','$Type':'Form','$Version':'42','Uuid':'0','Title':'Gemini Function Demo','AppName':'Gemini Function Demo','Scrollable':'True','Sizing':'Responsive','Theme':'AppTheme.Light.DarkActionBar','PrimaryColor':'&HFF245A81','PrimaryColorDark':'&HFF153951','AccentColor':'&HFF007D77','ShowListsAsJson':'True','$Components':[panel,ext]}
  return '#|\n$JSON\n'+json.dumps({'authURL':['creator.kodular.io'],'YaVersion':YA_VERSION,'Source':'Form','Properties':screen},separators=(',',':'))+'\n|#\n'
 def blocks(self):
  for i,n in enumerate(['callId','callName']):self.declare_global(n,self.literal(''),40+i*450)
  def reset():return [self.global_variable('callId',self.literal('')),self.global_variable('callName',self.literal('')),self.enabled('ReplyButton',False)]
  self.event('ConnectButton','Click',[self.conditional(self.compare(self.length(self.trim(self.prop('KeyInput','Text'))),'EQ',self.number(0)),[self.set_text('ErrorLabel','Enter your API key.')],[*reset(),self.set_text('ErrorLabel',''),self.enabled('AskButton',False),self.enabled('ConnectButton',False),self.enabled('DisconnectButton',True),self.set_text('StatusLabel','Connecting…'),self.method('GeminiLive1','ClearFunctions'),self.method('GeminiLive1','AddFunction',self.trim(self.prop('NameInput','Text')),self.prop('DescriptionInput','Text'),self.prop('SchemaInput','Text')),self.prop('GeminiLive1','Model',self.trim(self.prop('ModelInput','Text'))),self.prop('GeminiLive1','SystemInstruction',self.literal('You are testing app function calling. Use the declared function when requested, wait for its result, and briefly explain the returned result. Do not invent function results.')),self.method('GeminiLive1','Connect',self.trim(self.prop('KeyInput','Text')))])])
  self.event('DisconnectButton','Click',[self.method('GeminiLive1','Disconnect'),*reset(),self.enabled('AskButton',False),self.enabled('ConnectButton',True),self.enabled('DisconnectButton',False)])
  self.event('GeminiLive1','SetupComplete',[self.set_text('StatusLabel','Ready — function registered'),self.enabled('AskButton',True)])
  self.event('AskButton','Click',[self.conditional(self.method('GeminiLive1','IsReady'),[self.set_text('ResponseLabel',''),self.method('GeminiLive1','SendText',self.prop('PromptInput','Text'))],[self.set_text('ErrorLabel','Wait for SetupComplete.')])])
  self.event('GeminiLive1','FunctionCall',[self.conditional(self.prop('ReplyButton','Enabled'),[self.method('GeminiLive1','SendFunctionResult',self.parameter('callId'),self.parameter('name'),self.literal('{"ok":false,"error":"Another call is awaiting its result. Retry one at a time."}'))],[self.global_variable('callId',self.parameter('callId')),self.global_variable('callName',self.parameter('name')),self.prop('CallLabel','Text',self.join(self.literal('Function: '),self.parameter('name'))),self.prop('ArgsLabel','Text',self.join(self.literal('Arguments JSON: '),self.parameter('argumentsJson'))),self.prop('IdLabel','Text',self.join(self.literal('Call ID: '),self.parameter('callId'))),self.enabled('AskButton',False),self.enabled('ReplyButton',True),self.set_text('StatusLabel','Call received — inspect arguments, then return result.')])])
  self.event('ReplyButton','Click',[self.conditional(self.method('GeminiLive1','IsReady'),[self.method('GeminiLive1','SendFunctionResult',self.global_variable('callId'),self.global_variable('callName'),self.prop('ResultInput','Text')),self.set_text('StatusLabel','Result submitted — wait for Gemini reply.')],[self.set_text('ErrorLabel','Disconnected; reconnect before testing.')])])
  # Keep retry enabled if result JSON is invalid; after a successful result a duplicate is rejected by the extension.
  self.event('GeminiLive1','TurnComplete',[*reset(),self.prop('AskButton','Enabled',self.method('GeminiLive1','IsReady')),self.set_text('StatusLabel','Turn complete')])
  if 'FunctionCallCancelled' in self.supported_events:self.event('GeminiLive1','FunctionCallCancelled',[self.conditional(self.compare(self.parameter('callId'),'EQ',self.global_variable('callId')),[*reset(),self.prop('AskButton','Enabled',self.method('GeminiLive1','IsReady')),self.set_text('StatusLabel','Function call canceled.')])])
  self.event('GeminiLive1','Disconnected',[*reset(),self.enabled('AskButton',False),self.enabled('ConnectButton',True),self.enabled('DisconnectButton',False),self.prop('StatusLabel','Text',self.join(self.literal('Disconnected: '),self.parameter('reason')))])
  self.event('GeminiLive1','Error',[self.prop('ErrorLabel','Text',self.parameter('message')),self.prop('ConnectButton','Enabled',self.negate(self.method('GeminiLive1','IsConnected')))])
  self.event('Screen1','ErrorOccurred',[self.prop('ErrorLabel','Text',self.join(self.parameter('functionName'),self.literal(': '),self.parameter('message')))])
  for event in ['TextReceived','OutputTranscription']:self.event('GeminiLive1',event,self.append_transcript('ResponseLabel','text'))
  ET.SubElement(self.xml,'yacodeblocks',{'ya-version':YA_VERSION,'language-version':LANGUAGE_VERSION});ET.indent(self.xml,space='  ');return ET.tostring(self.xml,encoding='unicode')+'\n'
def main():
 parser=argparse.ArgumentParser();parser.add_argument('aix',type=Path);args=parser.parse_args()
 with zipfile.ZipFile(args.aix) as z:
  info=json.loads(z.read('com.fabiyon.geminilive/components.json'))[0];VERSIONS['GeminiLive']=str(info['version']);assets={'assets/external_comps/'+n:z.read(n) for n in z.namelist() if not n.endswith('/')}
 b=Demo();b.supported_events={e['name'] for e in info['events']};package='appinventor.ai_shafrchdd.GeminiFunctionDemo';source='src/'+package.replace('.','/')+'/Screen1';entries={source+'.scm':b.screen().encode(),source+'.bky':b.blocks().encode(),'youngandroidproject/project.properties':('\n'.join(['main='+package+'.Screen1','name=GeminiFunctionDemo','assets=../assets','source=../src','build=../build','versioncode=1','versionname=1.0','useslocation=False','aname=Gemini Function Demo','androidminsdk=23','sizing=Responsive','showlistsasjson=True','theme=AppTheme.Light.DarkActionBar','color.primary=&HFF245A81','color.primary.dark=&HFF153951','color.accent=&HFF007D77',''])).encode()}
 for n,data in entries.items():p=ROOT/'examples/function-demo/project'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
 entries.update(assets);output=ROOT/'examples/GeminiFunctionDemo.aia'
 with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as z:
  for n,data in sorted(entries.items()):
   item=zipfile.ZipInfo(n,(2026,10,12,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED;z.writestr(item,data)
 output.with_suffix('.aia.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n');print('Created',output,'using uploaded GeminiLive v'+str(info['version']),len(b.types),'components',b.top_count,'events')
if __name__=='__main__':main()
