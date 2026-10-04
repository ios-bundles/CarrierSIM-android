package ru.carriersim.android;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** A single, font-independent 24-unit outline icon family. */
final class LineIconView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String name;
    LineIconView(Context context,String name,int color){super(context);this.name=name;p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    private void line(Canvas c,float... points){Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float size=Math.min(getWidth(),getHeight());c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24,size/24);
        switch(name){
            case "sim":case "sim_off":{
                Path sim=new Path();sim.moveTo(8,3);sim.lineTo(18,3);sim.quadTo(20,3,20,5);sim.lineTo(20,19);sim.quadTo(20,21,18,21);sim.lineTo(6,21);sim.quadTo(4,21,4,19);sim.lineTo(4,7);sim.close();c.drawPath(sim,p);
                c.drawRoundRect(8,10,16,17,1,1,p);line(c,12,10,12,17);line(c,8,13.5f,16,13.5f);
                if(name.equals("sim_off"))line(c,2,2,22,22);break;
            }
            case "↓":line(c,12,4,12,16);line(c,7,11,12,16,17,11);line(c,5,18,5,21,19,21,19,18);break;
            case "↺":c.drawArc(5,5,20,20,220,290,false,p);line(c,4,4,4,10,10,10);break;
            case "↶":{
                Path shield=new Path();shield.moveTo(12,3);shield.lineTo(20,6);shield.lineTo(20,11);
                shield.cubicTo(20,16,16,20,12,22);shield.cubicTo(8,20,4,16,4,11);shield.lineTo(4,6);shield.close();c.drawPath(shield,p);
                line(c,8,12,11,15,16,10);break;
            }
            case "◎":c.drawCircle(12,12,2,p);c.drawArc(6,6,18,18,220,100,false,p);c.drawArc(2,2,22,22,220,100,false,p);line(c,12,15,9,21,15,21,12,15);break;
            case "☎":{
                Path phone=new Path();phone.moveTo(7,3);phone.lineTo(9,7);phone.quadTo(9.5f,8,8.5f,9);phone.lineTo(7,10.5f);
                phone.cubicTo(8.5f,13.5f,10.5f,15.5f,13.5f,17);phone.lineTo(15,15.5f);phone.quadTo(16,14.5f,17,15);phone.lineTo(21,17);
                phone.quadTo(22,17.5f,21.5f,19);phone.quadTo(21,22,18,21.5f);
                phone.cubicTo(10,20.5f,3.5f,14,2.5f,6);phone.quadTo(2,3,5,2.5f);phone.quadTo(6.5f,2,7,3);phone.close();c.drawPath(phone,p);break;
            }
            case "≡":line(c,5,6,19,6);line(c,5,12,19,12);line(c,5,18,15,18);break;
            case "▤":c.drawRoundRect(5,3,19,21,2,2,p);line(c,8,8,16,8);line(c,8,12,16,12);line(c,8,16,13,16);break;
            case "⌂":line(c,3,11,12,3,21,11);line(c,5,10,5,21,19,21,19,10);break;
            case "manual":line(c,4,6,7,6);line(c,11,6,20,6);line(c,4,12,13,12);line(c,17,12,20,12);line(c,4,18,7,18);line(c,11,18,20,18);c.drawCircle(9,6,2,p);c.drawCircle(15,12,2,p);c.drawCircle(9,18,2,p);break;
            case "›":line(c,9,6,15,12,9,18);break;
        }c.restore();
    }
}
