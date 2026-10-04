package ru.carriersim.android;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Owns the USB session and Python while the screen is recreated or backgrounded. */
public class CarrierService extends Service {
    public static final String UPDATE="ru.carriersim.android.UPDATE";
    public static volatile boolean busy=false, statusReady=false;
    public static volatile int deviceId=-1, disconnectedDeviceId=-1;
    public static volatile String action="", phase="", detail="", error="", outcome="";
    public static volatile long startedAt=0;
    public static volatile int duration=0, step=0;
    public static volatile boolean stopRequested=false;
    public static volatile String deviceJson="{}", simsJson="[]", diagnosticsJson="[]", report="", reportFile="";
    public static volatile String profilesJson="[]";
    public static volatile long revision=0;
    private static final StringBuilder screen=new StringBuilder();
    private static CarrierService active;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wake;
    private MuxBridge bridge;
    private long lastBroadcast=0;
    private final BroadcastReceiver detached=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            UsbDevice d=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(d!=null&&d.getDeviceId()==deviceId){
                statusReady=false;error="Соединение прервано. Подключите то же устройство снова.";
                MuxBridge session=bridge;if(session!=null)new Thread(session::close).start();update(true);
            }
        }
    };
    public static synchronized String getOutput(){return screen.toString();}
    public static void stopCapture(){if(isCapture())stopRequested=true;}
    public static boolean isCapture(){return busy&&(action.equals("diagnose")||action.equals("watch-call")||action.equals("report"));}
    public static void forgetDevice(){statusReady=false;deviceJson="{}";simsJson="[]";diagnosticsJson="[]";revision++;}
    public static void disconnect(){
        if(busy)return;
        disconnectedDeviceId=deviceId;forgetDevice();deviceId=-1;
        error="";outcome="";report="";reportFile="";
    }
    @Override public void onCreate(){
        super.onCreate();active=this;
        IntentFilter filter=new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(detached,filter,Context.RECEIVER_EXPORTED);else registerReceiver(detached,filter);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||busy)return START_NOT_STICKY;
        UsbDevice device=intent.getParcelableExtra("device");String requested=intent.getStringExtra("action");
        if(device==null||requested==null){stopSelf();return START_NOT_STICKY;}
        if(deviceId!=device.getDeviceId()){forgetDevice();deviceId=device.getDeviceId();}
        if(!requested.equals("status")){
            try{if(!DeviceGate.canOperate(device.getDeviceId(),deviceId,statusReady,false,new JSONObject(deviceJson).optBoolean("cellular",true),new JSONArray(simsJson).length())){stopSelf();return START_NOT_STICKY;}}
            catch(JSONException invalid){stopSelf();return START_NOT_STICKY;}
        }
        action=requested;busy=true;stopRequested=false;error="";outcome="";report="";reportFile="";
        phase="Подключение к устройству";detail="Разблокируйте iPhone и подтвердите доверие.";step=0;duration=0;startedAt=SystemClock.elapsedRealtime();
        if(action.equals("status"))statusReady=false;
        if(isCapture())diagnosticsJson="[]";
        synchronized(CarrierService.class){screen.setLength(0);}
        new File(getFilesDir(),"probe.log").delete();
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("operation","CarrierSIM",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"operation").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CarrierSIM · USB").setContentText(detail).setContentIntent(open).setOngoing(true).build();
        startForeground(1,notification);
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CarrierSIM:operation");wake.acquire(30*60*1000L);
        final String options=intent.getStringExtra("options")==null?"{}":intent.getStringExtra("options");
        worker.execute(()->{
            boolean success=false;
            try(MuxBridge session=new MuxBridge(this,(UsbManager)getSystemService(USB_SERVICE),device,this::emit)){
                bridge=session;session.start();
                if(!com.chaquo.python.Python.isStarted())com.chaquo.python.Python.start(new com.chaquo.python.android.AndroidPlatform(this));
                File root=new File(getFilesDir(),"carriersim");if(!root.exists()&&!root.mkdirs())throw new IOException("Не удалось создать папку CarrierSIM");
                for(String name:getAssets().list("carriersim")){
                    File destination=new File(root,name);if(name.equals("bundle.yaml")&&destination.exists())continue;
                    try(InputStream input=getAssets().open("carriersim/"+name);FileOutputStream file=new FileOutputStream(destination)){
                        byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)file.write(buffer,0,n);
                    }
                }
                success=com.chaquo.python.Python.getInstance().getModule("android_adapter")
                    .callAttr("run",root.getAbsolutePath(),session.getAddress(),session.getSerial(),this,requested,options).toBoolean();
            }catch(Exception failure){error=failure.getMessage()==null?failure.toString():failure.getMessage();emit("Ошибка: "+error);Log.e("CarrierSIM","Operation",failure);}
            finally{
                bridge=null;
                boolean attached=((UsbManager)getSystemService(USB_SERVICE)).getDeviceList().values().stream().anyMatch(d->d.getDeviceId()==deviceId);
                if(requested.equals("status"))statusReady=success&&attached;
                else if(!requested.equals("diagnose")&&!requested.equals("watch-call")&&!requested.equals("report"))statusReady=false;
                if(!attached)statusReady=false;
                if(success){outcome=requested.equals("status")?"Устройство готово":requested.equals("install")?"Профили установлены. Перезагрузите iPhone.":requested.equals("diagnose")||requested.equals("watch-call")||requested.equals("report")?"Сбор завершён · отчёт сохранён":"Восстановление завершено. Перезагрузите iPhone.";phase=outcome;detail="";}
                else{if(error.isEmpty())error="Действие не завершено. Откройте журнал для подробностей.";phase="Не удалось завершить действие";detail=error;}
                busy=false;if(wake!=null&&wake.isHeld())wake.release();update(true);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            }
        });
        update(true);return START_NOT_STICKY;
    }
    public boolean shouldStop(){return stopRequested;}
    public String getAction(){return action;}
    public void event(String json){
        try{
            JSONObject value=new JSONObject(json);
            switch(value.getString("type")){
                case "device":deviceJson=json;break;
                case "profiles":profilesJson=value.getJSONArray("items").toString();break;
                case "sims":simsJson=value.getJSONArray("items").toString();break;
                case "diagnostics":diagnosticsJson=value.getJSONArray("items").toString();break;
                case "phase":phase=value.getString("title");detail=value.optString("detail");duration=value.optInt("seconds");startedAt=SystemClock.elapsedRealtime();break;
                case "report":report=value.getString("text");reportFile=value.getString("file");break;
                case "error":error=value.getString("message");break;
            }
            update(false);
        }catch(JSONException invalid){Log.e("CarrierSIM","Invalid UI event",invalid);}
    }
    public void emit(String line){
        synchronized(CarrierService.class){screen.append(line).append('\n');if(screen.length()>100000)screen.delete(0,screen.length()-100000);}
        try(FileOutputStream file=new FileOutputStream(new File(getFilesDir(),"probe.log"),true)){file.write((line+"\n").getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}
        String text=line.trim();
        if(text.startsWith("[1/4]")||text.startsWith("[2/4]")||text.startsWith("[3/4]")||text.startsWith("[4/4]")){
            step=Character.digit(text.charAt(1),10);phase=text.substring(5).trim();detail="Не отключайте кабель.";
        }else if(text.startsWith("Ожидаю разблокировки")){phase="Подтвердите доверие";detail="Разблокируйте iPhone и нажмите «Доверять».";}
        else if(text.startsWith("Ошибка:")){error=text.substring(7).trim();}
        else if(text.contains("SIP ")||text.contains("звонок:")){detail=text;}
        update(false);
    }
    private synchronized void update(boolean force){
        revision++;
        long now=SystemClock.elapsedRealtime();
        if(force||now-lastBroadcast>=250){lastBroadcast=now;sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));}
    }
    @Override public void onDestroy(){unregisterReceiver(detached);worker.shutdown();if(active==this)active=null;super.onDestroy();}
}
