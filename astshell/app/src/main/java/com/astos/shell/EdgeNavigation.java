package com.astos.shell;
import android.content.*;import android.view.*;
/** No visible navigation controls. Platform-signed ROM service supplies edge gestures. */
final class EdgeNavigation {
 private final android.app.Service service;private View edge;private WindowManager manager;private float x,y;private long down;
 EdgeNavigation(android.app.Service service){this.service=service;}
 void start(){if(edge!=null)return;try{
  manager=(WindowManager)service.getSystemService(Context.WINDOW_SERVICE);edge=new View(service);edge.setBackgroundColor(android.graphics.Color.TRANSPARENT);
  WindowManager.LayoutParams p=new WindowManager.LayoutParams(Ui.dp(service,16),WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.TRANSLUCENT);
  p.gravity=Gravity.LEFT|Gravity.TOP;edge.setOnTouchListener((v,event)->{
   if(event.getActionMasked()==MotionEvent.ACTION_DOWN){x=event.getRawX();y=event.getRawY();down=event.getEventTime();return true;}
   if(event.getActionMasked()==MotionEvent.ACTION_UP){float dx=event.getRawX()-x,dy=event.getRawY()-y;
    if(-dy>Ui.dp(service,72)&&Math.abs(dy)>Math.abs(dx)){service.startActivity(new Intent(service,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));}
    else if(dx>Ui.dp(service,64)&&Math.abs(dx)>Math.abs(dy))back();
    return true;
   }return true;
  });manager.addView(edge,p);
 }catch(Exception e){edge=null;android.util.Log.w("AstOS","Edge navigation unavailable on non-ROM builds",e);}}
 private void back(){try{
  Class<?> type=Class.forName("android.hardware.input.InputManager");Object input=type.getMethod("getInstance").invoke(null);
  java.lang.reflect.Method inject=type.getMethod("injectInputEvent",android.view.InputEvent.class,int.class);long now=android.os.SystemClock.uptimeMillis();
  inject.invoke(input,new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),0);
  inject.invoke(input,new KeyEvent(now,now+1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),0);
 }catch(Exception e){android.util.Log.w("AstOS","Back gesture requires the platform-signed ROM",e);}}
 void stop(){if(edge!=null){try{manager.removeView(edge);}catch(Exception ignored){}edge=null;}}
}
