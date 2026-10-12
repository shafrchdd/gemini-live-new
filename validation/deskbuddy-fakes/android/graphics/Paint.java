package android.graphics; public class Paint {
 public static final int ANTI_ALIAS_FLAG=1; public enum Style{FILL,STROKE} public enum Cap{ROUND,BUTT} public enum Align{CENTER,LEFT,RIGHT}
 public int color=0xff000000; public Style style=Style.FILL;public float stroke=.012f,size=.1f;
 public Paint(int flags){} public void setColor(int v){color=v;}public void setStyle(Style v){style=v;}public void setStrokeWidth(float v){stroke=v;}public void setStrokeCap(Cap v){}public void setTextSize(float v){size=v;}public void setTextAlign(Align v){}
}
