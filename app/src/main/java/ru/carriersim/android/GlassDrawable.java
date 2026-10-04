package ru.carriersim.android;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Translucent material with a curved highlight and a thin refractive rim. */
final class GlassDrawable extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private final int top,bottom,rim;
    GlassDrawable(float radius,int top,int bottom,int rim){this.radius=radius;this.top=top;this.bottom=bottom;this.rim=rim;}
    @Override public void draw(Canvas canvas){
        Rect b=getBounds();RectF area=new RectF(b.left+1,b.top+1,b.right-1,b.bottom-1);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0,b.top,b.width()*0.75f,b.bottom,new int[]{top,bottom},null,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(area,radius,radius,paint);
        paint.setShader(new LinearGradient(0,b.top,0,b.bottom,new int[]{rim,0x08ffffff,0x38ffffff},new float[]{0,0.48f,1},Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(area,radius,radius,paint);
        paint.setShader(null);paint.setStyle(Paint.Style.FILL);
    }
    @Override public void getOutline(Outline outline){outline.setRoundRect(getBounds(),radius);}
    @Override public void setAlpha(int alpha){}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
