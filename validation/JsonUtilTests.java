import com.fabiyon.jsonutil.JsonUtil.Core;
import org.json.*;
public final class JsonUtilTests {
 static int passed;
 static void check(boolean value,String name) { if(!value)throw new AssertionError(name);passed++; }
 static void invalid(String text)throws Exception {try{Core.parse(text);throw new AssertionError("Accepted: "+text);}catch(IllegalArgumentException expected){passed++;}catch(JSONException expected){passed++;}}
 public static void main(String[]args)throws Exception {
  JSONObject o=Core.object("{\"emotion\":\"happy\",\"count\":3,\"active\":true,\"nested\":{\"x\":1},\"empty\":null}");
  check(o.getString("emotion").equals("happy"),"Read function argument");check(Core.type(o.get("count")).equals("number"),"Number type");check(Core.type(o.get("active")).equals("boolean"),"Boolean type");check(Core.type(o.get("empty")).equals("null"),"Null type");check(Core.blockValue(o.get("nested")) instanceof String,"Nested value stays JSON text");
  String text="Malayalam മലയാളം \"quoted\" \\ path\nnew line";String result=Core.put("{}","message",text);check(Core.object(result).getString("message").equals(text),"Escaping roundtrip");result=Core.put(result,"ok",true);result=Core.put(result,"details",Core.parse("{\"emotion\":\"happy\"}"));check(Core.object(result).getBoolean("ok"),"Result boolean");check(Core.object(result).getJSONObject("details").getString("emotion").equals("happy"),"Nested result");result=Core.put(result,"nullable",JSONObject.NULL);check(Core.object(result).has("nullable"),"Null retained");
  check(Core.encode(Core.parse("\"hello\"")).equals("\"hello\""),"Quoted scalar");check(Core.array("[1,true,null,{\"x\":1}]").length()==4,"Array");check(Core.type(Core.parse("-1.25e+2")).equals("number"),"Exponent");check(Core.type(Core.parse("true")).equals("boolean"),"Top boolean");check(Core.type(Core.parse("null")).equals("null"),"Top null");check(Core.parse(" \r\n\t{} ").toString().equals("{}"),"Allowed whitespace");
  for(String bad:new String[]{""," ","{","[] trailing","{'x':1}","{x:1}","{\"x\":1,}","[1,]","[01]","+1",".2","1.","1e","NaN","Infinity","1e999","\"bad\\q\"","\"bad\nline\"","\"\\u12x4\"","true false","[1;2]","{\"x\"=1}","undefined"})invalid(bad);
  String nested="0";for(int i=0;i<64;i++)nested="["+nested+"]";check(Core.parse(nested) instanceof JSONArray,"64 containers allowed");invalid("["+nested+"]");invalid(null);StringBuilder huge=new StringBuilder();for(int i=0;i<262145;i++)huge.append(' ');invalid(huge.toString());
  try{Core.object("[]");throw new AssertionError("Wrong root accepted");}catch(IllegalArgumentException expected){passed++;}
  try{Core.put("{}","bad",Double.NaN);throw new AssertionError("NaN stored");}catch(JSONException expected){passed++;}
  System.out.println("JsonUtil: "+passed+" checks passed");
 }
}
