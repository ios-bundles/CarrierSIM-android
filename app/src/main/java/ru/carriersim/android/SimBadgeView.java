package ru.carriersim.android;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** SIM-shaped badge with the line number and SIM technology inside the outline. */
final class SimBadgeView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int number,color;
    private final String kind;
    private final boolean present;
    SimBadgeView(Context context,int number,String kind,boolean present,int color){
        super(context);this.number=number;this.kind=kind;this.present=present;this.color=color;
        setContentDescription((kind.equals("eSIM")?"eSIM":"SIM")+" "+number+(present?"":" · нет активной линии"));
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float scale=Math.min(getWidth()/40f,getHeight()/50f);canvas.save();
        canvas.translate((getWidth()-40*scale)/2,(getHeight()-50*scale)/2);canvas.scale(scale,scale);
        Path outline=new Path();outline.moveTo(12,3);outline.lineTo(32,3);outline.quadTo(37,3,37,8);outline.lineTo(37,42);outline.quadTo(37,47,32,47);outline.lineTo(8,47);outline.quadTo(3,47,3,42);outline.lineTo(3,12);outline.close();
        paint.setColor((color&0x00ffffff)|0x12000000);paint.setStyle(Paint.Style.FILL);canvas.drawPath(outline,paint);
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.6f);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawPath(outline,paint);
        paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));paint.setTextSize(21);canvas.drawText(String.valueOf(number),20,29,paint);
        paint.setTextSize(8);canvas.drawText(kind.equals("eSIM")?"eSIM":"SIM",20,40,paint);
        if(!present){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);canvas.drawLine(1,2,39,48,paint);}canvas.restore();
    }
}
