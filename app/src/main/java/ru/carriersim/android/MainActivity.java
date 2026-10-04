package ru.carriersim.android;

import android.app.*;
import android.os.*;
import android.content.*;
import android.hardware.usb.*;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Native presentation; the Python core only sends data, progress and results. */
public class MainActivity extends Activity {
    private static final String PERMISSION="ru.carriersim.android.USB_PERMISSION";
    private int BG,CARD,INK,MUTED,BLUE;
    private int color(int resource){return getColor(resource);}
    private UsbManager usb;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout root,body,deviceCard,simList,menu,progressCard,resultCard,diagnosticList;
    private TextView connection,deviceName,deviceDetail,progressTitle,progressDetail,resultText,timer;
    private ProgressBar progress;
    private Button connect,stop,openReport;
    private TextView disconnect,reconnect;
    private final List<View> deviceActions=new ArrayList<>();
    private final List<View> historyActions=new ArrayList<>();
    private String page="home",pendingAction="status",pendingOptions="{}",lastSims="",lastDiagnostics="",lastDevice="";
    private int seenDevice=-1,attemptedDevice=-1;
    private boolean permissionPending=false;
    private TextView logView;
    private final Runnable tick=new Runnable(){public void run(){checkConnection();refresh();handler.postDelayed(this,600);}};
    private final BroadcastReceiver permissionReceiver=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
        if(PERMISSION.equals(intent.getAction())){
            permissionPending=false;UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)&&device!=null&&findDevice()!=null&&device.getDeviceId()==findDevice().getDeviceId())start(device,pendingAction,pendingOptions);
            else {CarrierService.error="Разрешение USB не получено. Нажмите «Подключиться» и разрешите доступ.";refresh();}
        }else refresh();
    }};
    private final BroadcastReceiver usbReceiver=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){checkConnection();refresh();}};
    // Android 8–12 use the legacy registration API; Android 13+ sets explicit export flags below.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        BG=color(R.color.theme_background);CARD=color(R.color.theme_card);INK=color(R.color.theme_ink);
        MUTED=color(R.color.theme_muted);BLUE=color(R.color.theme_accent);
        usb=(UsbManager)getSystemService(USB_SERVICE);
        IntentFilter local=new IntentFilter(PERMISSION);local.addAction(CarrierService.UPDATE);
        IntentFilter devices=new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);devices.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33){registerReceiver(permissionReceiver,local,Context.RECEIVER_NOT_EXPORTED);registerReceiver(usbReceiver,devices,Context.RECEIVER_EXPORTED);}
        else{registerReceiver(permissionReceiver,local);registerReceiver(usbReceiver,devices);}
        showHome();
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable background(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private boolean dark(){return (getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
    private Drawable glass(int radius,boolean accent){
        return new GlassDrawable(dp(radius),accent?color(R.color.theme_hero_start):color(R.color.glass_top),
            accent?color(R.color.theme_hero_end):color(R.color.glass_bottom),color(R.color.glass_rim));
    }
    private Drawable touch(Drawable surface,int radius){
        return new RippleDrawable(ColorStateList.valueOf(dark()?0x2479adff:0x18245fa8),surface,background(0xffffffff,radius));
    }
    private void material(View view,int radius,boolean accent){
        view.setBackground(glass(radius,accent));view.setElevation(dp(3));view.setClipToOutline(true);
        if(Build.VERSION.SDK_INT>=28){view.setOutlineAmbientShadowColor(0x18314565);view.setOutlineSpotShadowColor(0x20314565);}
    }
    private TextView text(LinearLayout parent,String value,int size,int color,boolean bold){
        TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(3),1);view.setFontFeatureSettings("kern");
        if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;
    }
    private TextView centeredText(LinearLayout parent,String value,int size,int color,boolean bold){
        TextView view=text(parent,value,size,color,bold);view.setGravity(Gravity.CENTER);return view;
    }
    private LinearLayout vertical(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout card(LinearLayout parent){
        LinearLayout view=vertical();view.setPadding(dp(20),dp(18),dp(20),dp(18));material(view,28,false);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(14);parent.addView(view,params);return view;
    }
    private void gap(LinearLayout parent,int height){View view=new View(this);parent.addView(view,new LinearLayout.LayoutParams(1,dp(height)));}
    private void section(LinearLayout parent,String title){TextView label=text(parent,title,11,MUTED,true);label.setLetterSpacing(0.12f);label.setPadding(dp(10),dp(16),0,dp(12));}
    private Button button(LinearLayout parent,String title,boolean primary,Runnable click){
        Button button=new Button(this);button.setText(title);button.setAllCaps(false);button.setTextSize(14);button.setTextColor(primary?color(R.color.primary_ink):INK);
        button.setBackgroundTintList(null);button.setBackground(touch(primary?new GlassDrawable(dp(24),color(R.color.primary_top),BLUE,color(R.color.glass_rim)):glass(24,false),24));button.setStateListAnimator(null);button.setElevation(0);button.setTranslationZ(0);button.setClipToOutline(true);button.setMinHeight(dp(48));button.setPadding(dp(12),dp(8),dp(12),dp(8));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(10);parent.addView(button,params);button.setOnClickListener(view->click.run());return button;
    }
    private void action(LinearLayout parent,String icon,String title,String subtitle,Runnable click){
        LinearLayout container=card(parent);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);container.addView(row);
        FrameLayout iconTile=new FrameLayout(this);iconTile.setBackground(glass(16,true));row.addView(iconTile,new LinearLayout.LayoutParams(dp(44),dp(44)));
        iconTile.addView(new LineIconView(this,icon,BLUE),new FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER));
        LinearLayout words=vertical();LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.leftMargin=dp(14);row.addView(words,wp);
        text(words,title,16,INK,true);text(words,subtitle,12,MUTED,false);
        LineIconView arrow=new LineIconView(this,"›",MUTED);LinearLayout.LayoutParams arrowParams=new LinearLayout.LayoutParams(dp(20),dp(20));arrowParams.leftMargin=dp(8);row.addView(arrow,arrowParams);
        container.setBackground(touch(glass(28,false),28));container.setOnClickListener(v->click.run());container.setFocusable(true);deviceActions.add(container);
    }
    private void shell(String title,String subtitle){
        FrameLayout stage=new FrameLayout(this);
        stage.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{color(R.color.canvas_start),BG,color(R.color.canvas_end)}));
        root=vertical();stage.addView(root,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout heading=vertical();heading.setPadding(dp(26),dp(14),dp(26),dp(20));root.addView(heading);
        TextView headingTitle=text(heading,title,30,INK,true);headingTitle.setLetterSpacing(-0.035f);
        text(heading,subtitle,12,MUTED,false);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        body=vertical();body.setClipToPadding(false);body.setClipChildren(false);body.setPadding(dp(18),dp(4),dp(18),dp(100));
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER_VERTICAL);nav.setPadding(dp(6),dp(6),dp(6),dp(6));material(nav,32,false);nav.setBackground(new GlassDrawable(dp(32),color(R.color.overlay_top),color(R.color.overlay_bottom),color(R.color.glass_rim)));nav.setElevation(dp(12));
        FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,dp(66),Gravity.BOTTOM);np.leftMargin=dp(22);np.rightMargin=dp(22);np.bottomMargin=dp(12);stage.addView(nav,np);
        navButton(nav,"⌂","Главная",page.equals("home"),()->showHome());
        navButton(nav,"▤","Отчёты",page.equals("history")||page.equals("report"),()->showHistory());
        navButton(nav,"≡","Журнал",page.equals("log"),()->showLog());
        stage.setOnApplyWindowInsetsListener((view,insets)->{
            root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),0);
            np.bottomMargin=insets.getSystemWindowInsetBottom()+dp(12);nav.setLayoutParams(np);
            body.setPadding(dp(18),dp(4),dp(18),dp(100)+insets.getSystemWindowInsetBottom());return insets;
        });
        setContentView(stage);stage.requestApplyInsets();
    }
    private void navButton(LinearLayout nav,String icon,String label,boolean selected,Runnable click){
        LinearLayout tab=vertical();tab.setGravity(Gravity.CENTER);tab.setPadding(0,dp(3),0,dp(3));
        tab.setBackground(touch(selected?glass(25,true):background(0x00000000,25),25));
        LineIconView symbol=new LineIconView(this,icon,selected?BLUE:MUTED);tab.addView(symbol,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView caption=text(tab,label,10,selected?BLUE:MUTED,selected);caption.setGravity(Gravity.CENTER);caption.setLineSpacing(0,1);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);params.leftMargin=dp(2);params.rightMargin=dp(2);nav.addView(tab,params);
        tab.setContentDescription(label);tab.setOnClickListener(v->click.run());
    }
    private void showHome(){
        page="home";deviceActions.clear();lastSims=lastDiagnostics=lastDevice="";shell("CarrierSIM · "+getString(R.string.core_version),"Профили операторов · iPhone / iPad");
        deviceCard=card(body);material(deviceCard,28,true);deviceCard.setPadding(dp(12),dp(12),dp(12),dp(12));
        LinearLayout deviceRow=new LinearLayout(this);deviceRow.setGravity(Gravity.CENTER_VERTICAL);deviceCard.addView(deviceRow);
        LinearLayout deviceWords=vertical();deviceRow.addView(deviceWords,new LinearLayout.LayoutParams(0,-2,1));
        connection=text(deviceWords,"USB · ожидание подключения",12,BLUE,true);gap(deviceWords,8);
        deviceName=text(deviceWords,"Подключите iPhone",24,INK,true);gap(deviceWords,4);
        deviceDetail=text(deviceWords,"Соедините iPhone и Android кабелем. Разблокируйте iPhone и подтвердите доверие.",13,color(R.color.theme_hero_detail),false);
        LinearLayout controls=vertical();controls.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsParams=new LinearLayout.LayoutParams(dp(152),-2);controlsParams.leftMargin=dp(12);deviceRow.addView(controls,controlsParams);
        reconnect=new TextView(this);reconnect.setText("Переподключение");reconnect.setTextSize(14);reconnect.setTextColor(BLUE);reconnect.setGravity(Gravity.CENTER);reconnect.setSingleLine(true);
        reconnect.setContentDescription("Переподключение");reconnect.setBackground(touch(glass(16,false),16));reconnect.setOnClickListener(v->refreshDevice());
        controls.addView(reconnect,new LinearLayout.LayoutParams(-1,dp(48)));
        disconnect=new TextView(this);disconnect.setText("Отключиться");disconnect.setTextSize(14);disconnect.setTextColor(INK);disconnect.setGravity(Gravity.CENTER);disconnect.setSingleLine(true);
        disconnect.setContentDescription("Отключиться от устройства");disconnect.setBackground(touch(glass(16,false),16));
        LinearLayout.LayoutParams disconnectParams=new LinearLayout.LayoutParams(-1,dp(48));disconnectParams.topMargin=dp(8);controls.addView(disconnect,disconnectParams);
        disconnect.setOnClickListener(v->{if(CarrierService.busy||permissionPending)return;CarrierService.disconnect();refresh();});
        connect=button(deviceCard,"Подключиться",true,()->refreshDevice());
        progressCard=card(body);progressTitle=text(progressCard,"",17,INK,true);progressDetail=text(progressCard,"",14,MUTED,false);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progressCard.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));timer=text(progressCard,"",12,BLUE,false);
        stop=button(progressCard,"Завершить сбор",false,()->{CarrierService.stopCapture();stop.setEnabled(false);stop.setText("Завершаю…");});
        resultCard=card(body);resultText=text(resultCard,"",14,INK,false);
        openReport=button(resultCard,"Открыть отчёт",false,()->viewReport(new File(new File(getFilesDir(),"carriersim/runs"),CarrierService.reportFile)));
        simList=vertical();body.addView(simList);
        diagnosticList=vertical();body.addView(diagnosticList);
        menu=vertical();body.addView(menu);section(menu,"ПРОФИЛИ");
        action(menu,"↓","Установить профили","План установки для выбранных SIM",()->writeDialog("install"));
        action(menu,"manual","Выбрать профиль вручную","Выбор SIM и профиля оператора",()->manualProfileDialog());
        action(menu,"↺","Штатные профили","Вернуть исходный профиль оператора",()->writeDialog("restore"));
        section(menu,"ДИАГНОСТИКА");
        action(menu,"◎","Состояние сети","IMS · VoWiFi · VoLTE · 5G · роуминг",()->captureDialog("diagnose"));
        action(menu,"☎","Диагностика звонка","Сеть звонка, SIP и согласованный кодек",()->captureDialog("watch-call"));
        action(menu,"≡","Отчёт о профиле","Журнал и результаты ручных проверок",()->captureDialog("report"));
        section(menu,"ВОССТАНОВЛЕНИЕ");
        action(menu,"↶","Восстановить после сбоя","Вернуть настройки из журнала операции",()->writeDialog("recover"));
        checkConnection();refresh();
    }
    private UsbDevice findDevice(){
        for(UsbDevice device:usb.getDeviceList().values())if(device.getVendorId()==0x05ac){
            for(int c=0;c<device.getConfigurationCount();c++){UsbConfiguration cfg=device.getConfiguration(c);for(int i=0;i<cfg.getInterfaceCount();i++){UsbInterface f=cfg.getInterface(i);if(f.getInterfaceClass()==255&&f.getInterfaceSubclass()==254&&f.getInterfaceProtocol()==2)return device;}}
        }return null;
    }
    private void checkConnection(){
        UsbDevice device=findDevice();int id=device==null?-1:device.getDeviceId();
        if(CarrierService.disconnectedDeviceId!=id)CarrierService.disconnectedDeviceId=-1;
        if(id!=seenDevice){seenDevice=id;attemptedDevice=-1;permissionPending=false;if(id!=CarrierService.deviceId||id==-1){CarrierService.forgetDevice();CarrierService.error="";}
            if(id!=-1&&(CarrierService.busy||CarrierService.statusReady)&&id==CarrierService.deviceId)attemptedDevice=id;
            lastDevice=lastSims=lastDiagnostics="";}
        if(device!=null&&CarrierService.disconnectedDeviceId!=id&&!CarrierService.busy&&!permissionPending&&attemptedDevice!=id){attemptedDevice=id;discover("status","{}");}
    }
    private void refreshDevice(){
        if(CarrierService.busy||permissionPending)return;
        CarrierService.disconnectedDeviceId=-1;discover("status","{}");
    }
    private void discover(String requested,String options){
        if(CarrierService.busy)return;UsbDevice device=findDevice();
        if(device==null){CarrierService.forgetDevice();refresh();return;}
        if(!requested.equals("status")&&(!CarrierService.statusReady||device.getDeviceId()!=CarrierService.deviceId))return;
        if(usb.hasPermission(device))start(device,requested,options);
        else {pendingAction=requested;pendingOptions=options;permissionPending=true;
            usb.requestPermission(device,PendingIntent.getBroadcast(this,0,new Intent(PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));refresh();}
    }
    private void start(UsbDevice device,String requested,String options){
        startForegroundService(new Intent(this,CarrierService.class).putExtra("device",device).putExtra("action",requested).putExtra("options",options));
    }
    private JSONObject object(String json){try{return new JSONObject(json);}catch(JSONException invalid){return new JSONObject();}}
    private JSONArray array(String json){try{return new JSONArray(json);}catch(JSONException invalid){return new JSONArray();}}
    private void refresh(){
        if(page.equals("log")){logView.setText(CarrierService.getOutput().isEmpty()?"Журнал появится после подключения устройства.":CarrierService.getOutput());return;}
        if(page.equals("history")){UsbDevice found=findDevice();boolean enabled=DeviceGate.canOperate(found==null?-1:found.getDeviceId(),CarrierService.deviceId,CarrierService.statusReady,CarrierService.busy,object(CarrierService.deviceJson).optBoolean("cellular",true),array(CarrierService.simsJson).length());for(View action:historyActions){action.setEnabled(enabled);action.setAlpha(enabled?1:0.4f);}return;}
        if(!page.equals("home"))return;
        UsbDevice device=findDevice();boolean attached=device!=null,ready=DeviceGate.ready(attached?device.getDeviceId():-1,CarrierService.deviceId,CarrierService.statusReady);
        JSONObject info=object(CarrierService.deviceJson);boolean cellular=info.optBoolean("cellular",true)&&array(CarrierService.simsJson).length()>0;
        boolean disconnected=attached&&CarrierService.disconnectedDeviceId==device.getDeviceId();
        connection.setText(disconnected?"USB · отключено в приложении":!attached?"USB · устройство не подключено":ready?"● Подключено по USB":permissionPending?"USB · требуется разрешение Android":"USB · проверка подключения");
        deviceName.setText(!attached?"Подключите iPhone":info.optString("name", "iPhone обнаружен"));
        deviceDetail.setText(!attached?"Соедините iPhone и Android кабелем. Разблокируйте iPhone и подтвердите доверие.":
            disconnected?"Нажмите «Подключиться», чтобы подключиться снова.":
            info.has("version")?(info.optString("family").equals("iPad")?"iPadOS ":"iOS ")+info.optString("version")+" · "+info.optString("build")+(!cellular?"\nАктивные сотовые линии не обнаружены.":""):
            "Разблокируйте iPhone и подтвердите «Доверять» на его экране.");
        connect.setText(disconnected?"Подключиться":attached?"Проверить подключение":"Проверить USB");connect.setEnabled(!CarrierService.busy&&!permissionPending);
        connect.setVisibility(ready||CarrierService.busy?View.GONE:View.VISIBLE);
        reconnect.setVisibility(ready?View.VISIBLE:View.GONE);reconnect.setEnabled(!CarrierService.busy&&!permissionPending);reconnect.setAlpha(reconnect.isEnabled()?1:0.35f);
        ((View)disconnect.getParent()).setVisibility(ready?View.VISIBLE:View.GONE);disconnect.setVisibility(ready?View.VISIBLE:View.GONE);disconnect.setEnabled(!CarrierService.busy&&!permissionPending);disconnect.setAlpha(disconnect.isEnabled()?1:0.35f);
        menu.setVisibility(ready&&!CarrierService.isCapture()?View.VISIBLE:View.GONE);simList.setVisibility(ready&&!CarrierService.isCapture()?View.VISIBLE:View.GONE);
        for(View action:deviceActions){action.setEnabled(DeviceGate.canOperate(attached?device.getDeviceId():-1,CarrierService.deviceId,CarrierService.statusReady,CarrierService.busy,cellular,array(CarrierService.simsJson).length()));action.setAlpha(action.isEnabled()?1:0.4f);}
        progressCard.setVisibility(CarrierService.busy?View.VISIBLE:View.GONE);
        progressTitle.setText(CarrierService.phase);progressDetail.setText(CarrierService.detail);
        boolean timed=CarrierService.duration>0,stepped=CarrierService.step>0;progress.setIndeterminate(!timed&&!stepped);
        if(timed){long elapsed=(SystemClock.elapsedRealtime()-CarrierService.startedAt)/1000;progress.setMax(CarrierService.duration);progress.setProgress((int)Math.min(elapsed,CarrierService.duration));timer.setText(Math.max(0,CarrierService.duration-elapsed)+" с · идёт сбор");}
        else{progress.setMax(4);progress.setProgress(CarrierService.step);timer.setText(stepped?"Этап "+CarrierService.step+" из 4":"Не отключайте кабель.");}
        stop.setVisibility(CarrierService.isCapture()?View.VISIBLE:View.GONE);stop.setEnabled(!CarrierService.stopRequested);stop.setText(CarrierService.stopRequested?"Завершаю…":"Завершить сбор");
        openReport.setVisibility(!CarrierService.reportFile.isEmpty()?View.VISIBLE:View.GONE);
        String result=!CarrierService.error.isEmpty()?CarrierService.error:CarrierService.outcome;
        resultCard.setVisibility(!CarrierService.busy&&attached&&!result.isEmpty()&&(!CarrierService.action.equals("status")||!CarrierService.error.isEmpty())?View.VISIBLE:View.GONE);resultText.setText(result);resultText.setTextColor(CarrierService.error.isEmpty()?color(R.color.theme_success):color(R.color.theme_error));
        if(!lastSims.equals(CarrierService.simsJson)){
            lastSims=CarrierService.simsJson;simList.removeAllViews();JSONArray sims=array(lastSims);section(simList,"ВАШИ SIM");
            LinearLayout row=new LinearLayout(this);row.setClipChildren(false);simList.addView(row,new LinearLayout.LayoutParams(-1,-2));
            for(int slot=0;slot<2;slot++){
                JSONObject sim=null;String key=slot==0?"kOne":"kTwo";
                for(int i=0;i<sims.length();i++){JSONObject item=sims.optJSONObject(i);if(item!=null&&key.equals(item.optString("slot"))){sim=item;break;}}
                boolean present=sim!=null;LinearLayout tile=vertical();tile.setPadding(dp(12),dp(14),dp(12),dp(12));tile.setGravity(Gravity.CENTER_HORIZONTAL);material(tile,24,false);
                LinearLayout.LayoutParams tileParams=new LinearLayout.LayoutParams(0,-1,1);if(slot==0)tileParams.rightMargin=dp(6);else tileParams.leftMargin=dp(6);row.addView(tile,tileParams);
                String kind=present?sim.optString("kind","unknown"):"SIM";
                LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);tile.addView(heading,new LinearLayout.LayoutParams(-1,-2));
                SimBadgeView icon=new SimBadgeView(this,slot+1,kind,present,present?BLUE:MUTED);heading.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(52)));
                LinearLayout labels=vertical();LinearLayout.LayoutParams labelsParams=new LinearLayout.LayoutParams(0,-2,1);labelsParams.leftMargin=dp(8);heading.addView(labels,labelsParams);
                if(!present){text(labels,"Нет SIM",14,MUTED,true);text(labels,"Линия не активна",10,MUTED,false);continue;}
                text(labels,sim.optString("operator"),15,INK,true);
                text(labels,sim.optString("plmn")+" · …"+sim.optString("iccid","—"),10,MUTED,false);
                if(kind.equals("unknown"))text(labels,"Тип не определён",9,MUTED,false);
                gap(tile,8);TextView current=centeredText(tile,sim.optString("current"),12,BLUE,true);
                current.setBackground(glass(12,true));current.setPadding(dp(6),dp(5),dp(6),dp(5));
            }
            gap(simList,8);
        }
        if(!lastDiagnostics.equals(CarrierService.diagnosticsJson)){lastDiagnostics=CarrierService.diagnosticsJson;diagnosticList.removeAllViews();JSONArray cards=array(lastDiagnostics);if(cards.length()>0){section(diagnosticList,"ДАННЫЕ ИЗ ЖУРНАЛА iOS");text(diagnosticList,"Слоты определяются по журналу. Если значение не пришло, оно остаётся неизвестным.",12,MUTED,false);gap(diagnosticList,10);}
            for(int i=0;i<cards.length();i++){JSONObject data=cards.optJSONObject(i);if(data==null)continue;LinearLayout card=card(diagnosticList);text(card,data.optString("title"),16,INK,true);gap(card,8);JSONArray items=data.optJSONArray("items");if(items==null)continue;
                for(int j=0;j<items.length();j++){JSONObject item=items.optJSONObject(j);if(item==null)continue;text(card,item.optString("label"),12,MUTED,false);if(!item.optString("value").isEmpty())text(card,item.optString("value"),15,INK,true);gap(card,6);}}}
        diagnosticList.setVisibility(ready?View.VISIBLE:View.GONE);
    }
    private Spinner spinner(LinearLayout parent,String[] values){Spinner view=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);view.setAdapter(adapter);parent.addView(view);return view;}
    private LinearLayout dialogBody(){LinearLayout view=vertical();view.setPadding(dp(22),dp(10),dp(22),dp(16));return view;}
    private void writeDialog(String requested){
        if(!CarrierService.statusReady||CarrierService.busy)return;
        LinearLayout content=dialogBody();text(content,requested.equals("install")?"Будут установлены профили по показанному плану. Android-версия экспериментальная. Копии сохраняются в приложении.":requested.equals("restore")?"Вернуть штатные профили выбранным SIM.":"Вернуть исходное состояние по журналам незавершённых операций.",14,INK,false);
        text(content,"Не отключайте кабель до завершения.",14,MUTED,false);
        Spinner sims=spinner(content,new String[]{"Все SIM","SIM 1","SIM 2"});if(requested.equals("recover"))sims.setVisibility(View.GONE);
        TextView plan=text(content,"",14,BLUE,true);
        sims.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(requested.equals("recover"))return;StringBuilder summary=new StringBuilder();JSONArray cards=array(CarrierService.simsJson);
                for(int i=0;i<cards.length();i++){JSONObject card=cards.optJSONObject(i);if(card==null)continue;
                    if(position!=0&&!card.optString("slot").equals(position==1?"kOne":"kTwo"))continue;
                    if(summary.length()>0)summary.append("\n");summary.append(card.optString("title")).append(" → ").append(requested.equals("restore")?"штатный профиль":card.optString(position==0?"plan":"explicitPlan"));
                }plan.setText(summary.length()==0?"Выбранная SIM не найдена.":summary.toString());
            }
        });
        new AlertDialog.Builder(this).setTitle(requested.equals("install")?"Установка профилей":requested.equals("restore")?"Штатные профили":"Восстановление после сбоя").setView(content).setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(dialog,which)->{
            JSONObject options=new JSONObject();try{options.put("sims",new String[]{"all","1","2"}[sims.getSelectedItemPosition()]);}catch(JSONException ignored){}discover(requested,options.toString());}).show();
    }
    private void manualProfileDialog(){
        if(!CarrierService.statusReady||CarrierService.busy)return;
        JSONArray cards=array(CarrierService.simsJson);List<String> titles=new ArrayList<>(),slots=new ArrayList<>();
        for(int i=0;i<cards.length();i++){JSONObject sim=cards.optJSONObject(i);if(sim==null)continue;
            String slot=sim.optString("slot");if(!slot.equals("kOne")&&!slot.equals("kTwo"))continue;
            titles.add(sim.optString("title")+" · "+sim.optString("operator"));slots.add(slot.equals("kOne")?"1":"2");}
        if(slots.isEmpty())return;
        LinearLayout content=dialogBody();text(content,"SIM для установки",13,MUTED,true);Spinner sims=spinner(content,titles.toArray(new String[0]));
        gap(content,12);text(content,"Профиль оператора",13,MUTED,true);
        AutoCompleteTextView profile=new AutoCompleteTextView(this);profile.setSingleLine(true);profile.setTextSize(16);profile.setTextColor(INK);profile.setHintTextColor(MUTED);
        profile.setHint("Например Vodafone_hu");profile.setThreshold(1);profile.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        JSONArray catalog=array(CarrierService.profilesJson);List<String> names=new ArrayList<>();for(int i=0;i<catalog.length();i++)names.add(catalog.optString(i));
        if(names.isEmpty())try{
            JSONArray cached=new JSONObject(read(new File(getFilesDir(),"carriersim/runs/bundles.json"))).getJSONArray("bundles");
            for(int i=0;i<cached.length();i++){String name=cached.getJSONObject(i).optString("b");if(name.matches("[A-Za-z0-9_]+"))names.add(name);}
            Collections.sort(names);
        }catch(IOException|JSONException ignored){}
        profile.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_dropdown_item_1line,names));content.addView(profile,new LinearLayout.LayoutParams(-1,dp(52)));
        if(!names.isEmpty())button(content,"Выбрать из списка",false,()->new AlertDialog.Builder(this).setTitle("Профили операторов").setItems(names.toArray(new String[0]),(d,which)->{profile.setText(names.get(which),false);profile.dismissDropDown();}).setNegativeButton("Отмена",null).show());
        gap(content,8);text(content,"Начните вводить название для поиска. Изменится только выбранная SIM. Исходные профили сохранятся в копии.",13,MUTED,false);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Выбрать профиль вручную").setView(content).setNegativeButton("Отмена",null).setPositiveButton("Продолжить",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=profile.getText().toString().trim().replaceFirst("\\.bundle$","");
            if(!name.matches("[A-Za-z0-9_]+")){profile.setError("Введите название профиля латиницей");return;}
            if(!names.isEmpty()&&!names.contains(name)){profile.setError("Выберите профиль из списка");return;}
            String slot=slots.get(sims.getSelectedItemPosition()),simTitle=titles.get(sims.getSelectedItemPosition());
            new AlertDialog.Builder(this).setTitle("Установить выбранный профиль?").setMessage(simTitle+" → "+name+"\n\nНе отключайте кабель до завершения.").setNegativeButton("Отмена",null).setPositiveButton("Установить",(d2,w)->{
                JSONObject options=new JSONObject();try{options.put("sims",slot);options.put("bundle",name);}catch(JSONException ignored){}
                dialog.dismiss();discover("install",options.toString());}).show();
        }));dialog.show();
    }
    private static final String[] QUESTIONS={"VoWiFi: звонок в авиарежиме","VoWiFi: включается автоматически","VoLTE: звонок остаётся в 4G/5G","5G: полоса n в Field Test","iMessage / FaceTime с номера","SMS по Wi-Fi в авиарежиме","Режим модема","Объединение и удержание вызовов"};
    private void captureDialog(String requested){
        LinearLayout content=dialogBody();text(content,requested.equals("watch-call")?"Сделайте тестовый звонок во время сбора. Для VoWiFi: авиарежим + Wi-Fi. Для VoLTE: выключите Wi-Fi.":"Во время сбора включите авиарежим на 10 секунд и выключите. Wi-Fi оставьте включённым.",14,INK,false);
        text(content,"Длительность сбора",13,MUTED,true);Spinner duration=spinner(content,new String[]{"15 секунд","30 секунд","90 секунд","180 секунд"});duration.setSelection(requested.equals("watch-call")?3:2);
        Map<String,List<View>> answers=new LinkedHashMap<>();EditText region=new EditText(this);region.setHint("Город или регион · необязательно");region.setTextColor(INK);region.setHintTextColor(MUTED);
        if(requested.equals("report")){
            JSONArray sims=array(CarrierService.simsJson);section(content,"ЧТО ВЫ ПРОВЕРИЛИ САМИ");
            for(int i=0;i<sims.length();i++){JSONObject sim=sims.optJSONObject(i);if(sim==null)continue;text(content,sim.optString("title")+" · "+sim.optString("operator"),16,INK,true);List<View> list=new ArrayList<>();answers.put(sim.optString("slot"),list);
                for(int q=0;q<QUESTIONS.length;q++){text(content,QUESTIONS[q],13,MUTED,false);if(q==3){EditText band=new EditText(this);band.setHint("Например n1 · оставьте пустым, если не проверяли");band.setHintTextColor(MUTED);band.setTextColor(INK);content.addView(band);list.add(band);}else list.add(spinner(content,new String[]{"Не проверял","Да","Нет"}));}}
            content.addView(region);
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle(requested.equals("watch-call")?"Диагностика звонка":requested.equals("report")?"Отчёт о профиле":"Диагностика сети").setView(scroll).setNegativeButton("Отмена",null).setPositiveButton("Начать",(dialog,which)->{
            JSONObject options=new JSONObject();try{options.put("seconds",new int[]{15,30,90,180}[duration.getSelectedItemPosition()]);
                if(requested.equals("report")){JSONObject values=new JSONObject();for(Map.Entry<String,List<View>> entry:answers.entrySet()){JSONArray a=new JSONArray();for(View v:entry.getValue())a.put(v instanceof Spinner?new String[]{"не проверял","да","нет"}[((Spinner)v).getSelectedItemPosition()]:((EditText)v).getText().toString());values.put(entry.getKey(),a);}options.put("answers",values);options.put("region",region.getText().toString());}}
            catch(JSONException ignored){}discover(requested,options.toString());}).show();
    }
    private void showLog(){page="log";shell("Журнал","Подробности текущего запуска");LinearLayout card=card(body);logView=text(card,CarrierService.getOutput(),12,MUTED,false);logView.setTextIsSelectable(true);button(card,"Поделиться журналом",false,()->share(new File(getFilesDir(),"probe.log")));refresh();}
    private void showHistory(){
        page="history";historyActions.clear();shell("Отчёты и копии","Сохранены на этом Android");
        File runs=new File(getFilesDir(),"carriersim/runs");File[] folders=runs.listFiles(File::isDirectory);
        if(folders==null||folders.length==0){LinearLayout card=card(body);text(card,"Пока нет сохранённых запусков",18,INK,true);text(card,"После диагностики здесь появятся отчёты. После установки — копии для восстановления.",14,MUTED,false);return;}
        Arrays.sort(folders,Comparator.comparingLong(File::lastModified).reversed());
        for(File folder:folders){File report=new File(folder,"report.txt"),snapshot=new File(folder,"snapshot/journal.json");
            LinearLayout card=card(body);String name=folder.getName();text(card,name.replace("-diagnose"," · сеть").replace("-watch-call"," · звонок").replace("-report"," · отчёт"),15,INK,true);
            if(report.isFile()){button(card,"Открыть отчёт",false,()->viewReport(report));button(card,"Поделиться отчётом",false,()->share(report));}
            if(snapshot.isFile()){
                text(card,"Есть копия исходных профилей",13,BLUE,false);Button restore=button(card,"Восстановить из этой копии",false,()->new AlertDialog.Builder(this).setTitle("Восстановить копию?").setMessage("Копия: "+name+". Ядро проверит, что она принадлежит подключённому устройству. Не отключайте кабель.").setNegativeButton("Отмена",null).setPositiveButton("Восстановить",(d,w)->{
                    JSONObject options=new JSONObject();try{options.put("backup",name);}catch(JSONException ignored){}showHome();discover("restore-backup",options.toString());}).show());historyActions.add(restore);UsbDevice device=findDevice();restore.setEnabled(DeviceGate.canOperate(device==null?-1:device.getDeviceId(),CarrierService.deviceId,CarrierService.statusReady,CarrierService.busy,object(CarrierService.deviceJson).optBoolean("cellular",true),array(CarrierService.simsJson).length()));
            }
            button(card,"Сохранить файлы запуска ZIP",false,()->archive(folder));
        }
    }
    private String read(File file)throws IOException{try(FileInputStream stream=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1)out.write(buffer,0,n);return out.toString("UTF-8");}}
    private void viewReport(File file){page="report";shell("Отчёт",file.getParentFile().getName());LinearLayout card=card(body);try{TextView view=text(card,read(file),14,INK,false);view.setTextIsSelectable(true);}catch(IOException e){text(card,"Не удалось прочитать отчёт",14,INK,false);}button(card,"Поделиться",true,()->share(file));}
    private void archive(File folder){
        new AlertDialog.Builder(this).setTitle("Сохранить файлы запуска?").setMessage("Архив содержит копии и журналы, в том числе данные устройства и SIM. Выберите, куда его отправить.").setNegativeButton("Отмена",null).setPositiveButton("Создать ZIP",(d,w)->{
            Toast.makeText(this,"Создаю архив…",Toast.LENGTH_SHORT).show();new Thread(()->{try{File exports=new File(getCacheDir(),"exports");exports.mkdirs();File zip=new File(exports,folder.getName()+".zip");try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(zip))){zipFolder(folder,folder,output);}runOnUiThread(()->share(zip));}catch(IOException e){runOnUiThread(()->Toast.makeText(this,"Не удалось создать архив",Toast.LENGTH_LONG).show());}}).start();}).show();
    }
    private void zipFolder(File base,File folder,ZipOutputStream output)throws IOException{File[] files=folder.listFiles();if(files==null)return;for(File file:files){if(file.isDirectory())zipFolder(base,file,output);else{output.putNextEntry(new ZipEntry(base.toPath().relativize(file.toPath()).toString()));try(FileInputStream input=new FileInputStream(file)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}output.closeEntry();}}}
    private void share(File source){
        if(!source.isFile()){Toast.makeText(this,"Файл ещё не создан",Toast.LENGTH_SHORT).show();return;}
        try{File exports=new File(getCacheDir(),"exports");exports.mkdirs();File target=new File(exports,source.getName());if(!source.getCanonicalPath().equals(target.getCanonicalPath()))try(FileInputStream input=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);}
            Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".exports").appendPath(target.getName()).build();Intent send=new Intent(Intent.ACTION_SEND).setType(target.getName().endsWith(".zip")?"application/zip":"text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("CarrierSIM",uri));startActivity(Intent.createChooser(send,"Поделиться файлом"));
        }catch(IOException e){Toast.makeText(this,"Не удалось сохранить файл",Toast.LENGTH_LONG).show();}
    }
    @Override public void onResume(){super.onResume();handler.post(tick);}
    @Override public void onPause(){handler.removeCallbacks(tick);super.onPause();}
    @Override public void onBackPressed(){if(!page.equals("home"))showHome();else super.onBackPressed();}
    @Override public void onDestroy(){handler.removeCallbacks(tick);unregisterReceiver(permissionReceiver);unregisterReceiver(usbReceiver);super.onDestroy();}
}
