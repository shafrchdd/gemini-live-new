package com.google.appinventor.components.runtime.util;
import java.util.*;
public class YailList {
  private final Object[] values;
  private YailList(Object[] values) { this.values = values; }
  public static YailList makeList(List<?> values) { return new YailList(values.toArray()); }
  public static YailList makeList(Object[] values) { return new YailList(values.clone()); }
  public int size() { return values.length; }
  public Object[] toArray() { return values.clone(); }
  public String toString() { return Arrays.toString(values); }
}
