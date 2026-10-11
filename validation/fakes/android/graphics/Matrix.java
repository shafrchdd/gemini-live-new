package android.graphics;
public final class Matrix {
  public float rotation, scaleX = 1f, scaleY = 1f;
  public void setRotate(float degrees) { rotation = degrees; scaleX = scaleY = 1f; }
  public void setScale(float x, float y) { scaleX = x; scaleY = y; rotation = 0f; }
  public boolean postScale(float x, float y) { scaleX *= x; scaleY *= y; return true; }
}
