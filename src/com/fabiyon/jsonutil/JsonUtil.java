package com.fabiyon.jsonutil;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.runtime.util.YailList;
import org.json.*;
import java.util.*;

@DesignerComponent(version=1, description="Small standalone JSON utility. Read function arguments, safely build results, validate JSON and inspect arrays. Nested values stay JSON text. No permissions or network dependency.", category=ComponentCategory.EXTENSION, nonVisible=true, iconName="")
@SimpleObject(external=true)
public final class JsonUtil extends AndroidNonvisibleComponent {
  private String lastError = "";
  public JsonUtil(ComponentContainer container) { super(container.$form()); }
  @SimpleProperty(description="Most recent operation error; cleared at the start of each operation. IsValid reports through this property without firing Error.") public String LastError() { return lastError; }
  @SimpleEvent public void Error(String operation, String message) { EventDispatcher.dispatchEvent(this,"Error",operation,message); }
  private void begin() { lastError=""; }
  private void fail(String operation,Exception e) { lastError=e.getMessage()==null?"Invalid JSON":e.getMessage();Error(operation,lastError); }
  @SimpleFunction(description="Check strict JSON syntax, including full input consumption. Returns false without firing Error. Limit: 262144 characters and 64 nested containers.")
  public boolean IsValid(String json) { begin();try { Core.parse(json);return true; } catch(Exception e) { lastError=e.getMessage();return false; } }
  @SimpleFunction(description="Read an object key. Strings/numbers/booleans are native block values; objects/arrays are JSON text. Missing keys and JSON null return fallback. Wrong input fires Error.")
  public Object GetValue(String json,String key,Object fallback) { begin();try { Object value=Core.object(json).opt(key);return value==null||value==JSONObject.NULL?fallback:Core.blockValue(value); } catch(Exception e) { fail("GetValue",e);return fallback; } }
  @SimpleFunction(description="Return a key's value as valid JSON text, including quotes around strings. Missing keys return fallbackJson unchanged.")
  public String GetJson(String json,String key,String fallbackJson) { begin();try { JSONObject o=Core.object(json);return o.has(key)?Core.encode(o.get(key)):fallbackJson; } catch(Exception e) { fail("GetJson",e);return fallbackJson; } }
  @SimpleFunction public boolean HasKey(String json,String key) { begin();try { return Core.object(json).has(key); } catch(Exception e) { fail("HasKey",e);return false; } }
  @SimpleFunction(description="Return a key type: string, number, boolean, object, array, null, or missing. Invalid input returns error.")
  public String ValueType(String json,String key) { begin();try { JSONObject o=Core.object(json);return o.has(key)?Core.type(o.get(key)):"missing"; } catch(Exception e) { fail("ValueType",e);return "error"; } }
  @SimpleFunction public YailList Keys(String json) { begin();try { List<String> keys=new ArrayList<String>();Iterator<String> it=Core.object(json).keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys);return YailList.makeList(keys); } catch(Exception e) { fail("Keys",e);return YailList.makeEmptyList(); } }
  @SimpleFunction(description="Safely add/replace a text value in an object and return new JSON. Input is unchanged. Failure returns original JSON.")
  public String SetText(String json,String key,String value) { return set("SetText",json,key,value==null?"":value); }
  @SimpleFunction(description="Add/replace a finite numeric value. NaN and infinity are rejected.")
  public String SetNumber(String json,String key,double value) { return set("SetNumber",json,key,Double.valueOf(value)); }
  @SimpleFunction public String SetBoolean(String json,String key,boolean value) { return set("SetBoolean",json,key,Boolean.valueOf(value)); }
  @SimpleFunction(description="Add/replace a value supplied as JSON text: object, array, quoted string, number, boolean or null. Use this for nested data.")
  public String SetJson(String json,String key,String valueJson) { begin();try { return Core.put(json,key,Core.parse(valueJson)); } catch(Exception e) { fail("SetJson",e);return json; } }
  private String set(String operation,String json,String key,Object value) { begin();try { return Core.put(json,key,value); } catch(Exception e) { fail(operation,e);return json; } }
  @SimpleFunction public String RemoveKey(String json,String key) { begin();try { JSONObject o=Core.object(json);o.remove(key);return o.toString(); } catch(Exception e) { fail("RemoveKey",e);return json; } }
  @SimpleFunction(description="Quote and escape text safely as a JSON string value, including its surrounding quotes.")
  public String Quote(String text) { begin();return JSONObject.quote(text==null?"":text); }
  @SimpleFunction(description="Number of items in a JSON array. Invalid input returns -1 and fires Error.")
  public int ArrayLength(String json) { begin();try { return Core.array(json).length(); } catch(Exception e) { fail("ArrayLength",e);return -1; } }
  @SimpleFunction(description="Read an array item using Kodular's 1-based index. Primitive values stay native; nested containers are JSON text. Null returns fallback; out-of-range fires Error.")
  public Object ArrayGet(String json,int index,Object fallback) { begin();try { JSONArray a=Core.array(json);if(index<1||index>a.length())throw new IllegalArgumentException("Array index out of range (use 1-based indexes)");Object value=a.get(index-1);return value==JSONObject.NULL?fallback:Core.blockValue(value); } catch(Exception e) { fail("ArrayGet",e);return fallback; } }

  /** Pure implementation, also used for local host tests. */
  public static final class Core {
    public static Object parse(String text) throws JSONException {
      new Syntax(text).validate();char first=text.trim().charAt(0);
      if(first=='{')return new JSONObject(text);if(first=='[')return new JSONArray(text);
      // An array wrapper avoids Android JSONTokener's number/string ambiguity for scalar inputs.
      return new JSONArray("["+text+"]").get(0);
    }
    public static JSONObject object(String text) throws JSONException { Object o=parse(text);if(!(o instanceof JSONObject))throw new IllegalArgumentException("Expected a JSON object");return (JSONObject)o; }
    public static JSONArray array(String text) throws JSONException { Object o=parse(text);if(!(o instanceof JSONArray))throw new IllegalArgumentException("Expected a JSON array");return (JSONArray)o; }
    public static String put(String json,String key,Object value) throws JSONException { if(key==null)throw new IllegalArgumentException("Key cannot be null");JSONObject o=object(json);o.put(key,value==null?JSONObject.NULL:value);return o.toString(); }
    public static Object blockValue(Object value) { return value instanceof JSONObject||value instanceof JSONArray?value.toString():value; }
    public static String encode(Object value) { if(value==null||value==JSONObject.NULL)return "null";return value instanceof String?JSONObject.quote((String)value):value.toString(); }
    public static String type(Object value) { if(value==null||value==JSONObject.NULL)return "null";if(value instanceof JSONObject)return "object";if(value instanceof JSONArray)return "array";if(value instanceof Boolean)return "boolean";if(value instanceof Number)return "number";return "string"; }
  }
  /** Strict syntax guard; Android's built-in org.json parser also accepts non-JSON forms. */
  private static final class Syntax {
    final String s;int p;
    Syntax(String value) { if(value==null||value.length()>262144)throw new IllegalArgumentException("JSON must be non-null and at most 262144 characters");s=value; }
    void bad() { throw new IllegalArgumentException("Invalid JSON near character "+(p+1)); }
    void ws() { while(p<s.length()&&(s.charAt(p)==' '||s.charAt(p)=='\n'||s.charAt(p)=='\r'||s.charAt(p)=='\t'))p++; }
    boolean take(char c) { ws();if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false; }
    void validate() { ws();value(0);ws();if(p!=s.length())bad(); }
    void value(int depth) {
      ws();if(p>=s.length())bad();char c=s.charAt(p);
      if(c=='{'||c=='[') { if(depth>=64)throw new IllegalArgumentException("JSON nesting exceeds 64 containers");p++;if(c=='{'){if(take('}'))return;do {ws();string();if(!take(':'))bad();value(depth+1);}while(take(','));if(!take('}'))bad();}else {if(take(']'))return;do {value(depth+1);}while(take(','));if(!take(']'))bad();}return; }
      if(c=='"'){string();return;}
      for(String literal:new String[]{"true","false","null"})if(s.startsWith(literal,p)){p+=literal.length();return;}
      number();
    }
    void string() {
      if(p>=s.length()||s.charAt(p++)!='"')bad();
      while(p<s.length()){char c=s.charAt(p++);if(c=='"')return;if(c<32)bad();if(c=='\\'){if(p>=s.length())bad();char e=s.charAt(p++);if(e=='u'){for(int i=0;i<4;i++){if(p>=s.length()||"0123456789abcdefABCDEF".indexOf(s.charAt(p++))<0)bad();}}else if("\"\\/bfnrt".indexOf(e)<0)bad();}}
      bad();
    }
    boolean digit() { return p<s.length()&&s.charAt(p)>='0'&&s.charAt(p)<='9'; }
    void number() {
      int start=p;if(p<s.length()&&s.charAt(p)=='-')p++;
      if(!digit())bad();if(s.charAt(p)=='0')p++;else while(digit())p++;
      if(p<s.length()&&s.charAt(p)=='.'){p++;if(!digit())bad();while(digit())p++;}
      if(p<s.length()&&(s.charAt(p)=='e'||s.charAt(p)=='E')){p++;if(p<s.length()&&(s.charAt(p)=='+'||s.charAt(p)=='-'))p++;if(!digit())bad();while(digit())p++;}
      double n=Double.parseDouble(s.substring(start,p));if(Double.isInfinite(n)||Double.isNaN(n))throw new IllegalArgumentException("Number exceeds supported finite range");
    }
  }
}
