package ru.carriersim.android;

import android.content.Context;
import android.hardware.usb.*;
import android.os.SystemClock;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalServerSocket;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** App-private usbmuxd-compatible bridge: Python sees sockets, Android owns USB permission. */
public final class MuxBridge implements AutoCloseable {
    private final Context context; private final UsbManager manager; private final UsbDevice device; private final Consumer<String> log;
    private UsbDeviceConnection usb; private UsbInterface iface; private UsbEndpoint in,out;
    private int version, muxTx;
    private volatile int muxRx=65535;
    private final AtomicBoolean closed=new AtomicBoolean();
    private byte[] pending=new byte[0];
    private volatile boolean live;
    private LocalServerSocket server;
    private LocalSocket boundSocket;
    private String address;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Map<Integer,Channel> channels=new ConcurrentHashMap<>();
    private final Set<LocalSocket> clients=ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextPort=new AtomicInteger(49152);
    private String serial,buid;
    public MuxBridge(Context c,UsbManager m,UsbDevice d,Consumer<String> l) {context=c.getApplicationContext();manager=m;device=d;log=l;}
    public String getAddress() {return address;}
    public String getSerial() {return serial;}
    public void start() throws Exception {
        usb=manager.openDevice(device);if(usb==null)throw new IOException("Android не открыл iPhone");
        String raw=device.getSerialNumber().replace("\u0000","").trim();
        serial=raw.length()==24?raw.substring(0,8)+"-"+raw.substring(8):raw;
        android.content.SharedPreferences prefs=context.getSharedPreferences("pairing",0);
        buid=prefs.getString("buid",null);if(buid==null){buid=UUID.randomUUID().toString().toUpperCase(Locale.ROOT);prefs.edit().putString("buid",buid).apply();}
        UsbConfiguration selected=null;
        for(int c=0;c<device.getConfigurationCount();c++) {
            UsbConfiguration cfg=device.getConfiguration(c);
            for(int i=0;i<cfg.getInterfaceCount();i++) {
                UsbInterface candidate=cfg.getInterface(i);
                if(candidate.getInterfaceClass()==255&&candidate.getInterfaceSubclass()==254&&candidate.getInterfaceProtocol()==2&&(selected==null||cfg.getId()==3)) {selected=cfg;iface=candidate;}
            }
        }
        if(selected==null||!usb.setConfiguration(selected)||!usb.claimInterface(iface,true))throw new IOException("Нет доступа к Apple USB Multiplexor");
        for(int i=0;i<iface.getEndpointCount();i++){UsbEndpoint e=iface.getEndpoint(i);if(e.getType()==2){if(e.getDirection()==128)in=e;else out=e;}}
        if(in==null||out==null)throw new IOException("Нет USB endpoints");
        sendMux(0,ByteBuffer.allocate(12).putInt(2).putInt(0).putInt(0).array());
        byte[] hello=receiveMux(SystemClock.elapsedRealtime()+8000);ByteBuffer h=ByteBuffer.wrap(hello);
        if(hello.length<20||h.getInt()!=0)throw new IOException("Нет ответа USB mux");h.getInt();version=h.getInt();
        if(version!=1&&version!=2)throw new IOException("Неизвестная версия USB mux");if(version==2)sendMux(2,new byte[]{7});
        address=new File(context.getFilesDir(),"usbmuxd.sock").getAbsolutePath();
        new File(address).delete();boundSocket=new LocalSocket();
        boundSocket.bind(new LocalSocketAddress(address,LocalSocketAddress.Namespace.FILESYSTEM));
        server=new LocalServerSocket(boundSocket.getFileDescriptor());live=true;
        workers.execute(this::receiveLoop);workers.execute(this::acceptLoop);
        log.accept("USB-мост готов · Python подключается локально");
    }
    private synchronized void sendMux(int protocol,byte[] data) throws IOException {
        int header=version==2?16:8;ByteBuffer b=ByteBuffer.allocate(header+data.length);b.putInt(protocol).putInt(b.capacity());
        if(version==2){if(protocol==2){muxTx=0;muxRx=65535;}b.putInt(0xfeedface).putShort((short)muxTx++).putShort((short)muxRx);}
        b.put(data);byte[] bytes=b.array();if(usb.bulkTransfer(out,bytes,bytes.length,5000)!=bytes.length)throw new IOException("USB запись не завершена");
        if(bytes.length%out.getMaxPacketSize()==0&&usb.bulkTransfer(out,new byte[0],0,1000)<0)throw new IOException("USB ZLP не отправлен");
    }
    private byte[] receiveMux(long deadline) throws IOException {
        while(SystemClock.elapsedRealtime()<deadline&&!Thread.currentThread().isInterrupted()) {
            if(pending.length>=8){int n=ByteBuffer.wrap(pending).getInt(4);if(n<8||n>1048576)throw new IOException("Некорректная длина USB mux");if(pending.length>=n){byte[] p=Arrays.copyOf(pending,n);pending=Arrays.copyOfRange(pending,n,pending.length);if(version==2){if(n<16)throw new IOException("Короткий mux header");muxRx=ByteBuffer.wrap(p).getShort(12)&65535;}return p;}}
            byte[] buffer=new byte[16384];int n=usb.bulkTransfer(in,buffer,buffer.length,1000);if(n>0){if(pending.length+n>1048576)throw new IOException("USB mux overflow");pending=join(pending,Arrays.copyOf(buffer,n));}
        }
        throw new SocketTimeoutException("Нет данных USB");
    }
    private void receiveLoop() {
        try {while(live){byte[] p;try{p=receiveMux(SystemClock.elapsedRealtime()+1500);}catch(SocketTimeoutException e){continue;}int offset=version==2?16:8;ByteBuffer b=ByteBuffer.wrap(p);if(b.getInt(0)!=6||p.length<offset+20)continue;Channel ch=channels.get(b.getShort(offset+2)&65535);if(ch!=null&&(b.getShort(offset)&65535)==ch.destination)ch.receive(p,offset);}}
        catch(Exception e){if(live)log.accept("USB-соединение прервано: "+e.getMessage());close();}
    }
    private void acceptLoop(){try{while(live){LocalSocket s=server.accept();if(s.getPeerCredentials().getUid()!=android.os.Process.myUid()){s.close();continue;}clients.add(s);workers.execute(()->handle(s));}}catch(IOException e){if(live)log.accept("USB-мост закрыт");}}
    private Map<String,Object> attached() {
        return map("MessageType","Attached","DeviceID",1,"Properties",map("DeviceID",1,"SerialNumber",serial,"ConnectionType","USB","ProductID",device.getProductId(),"LocationID",1));
    }
    private void handle(LocalSocket client) {
        Channel channel=null;
        try {
            client.setSoTimeout(15000);InputStream input=client.getInputStream();OutputStream output=client.getOutputStream();
            while(live){byte[] head=readExactly(input,16);ByteBuffer b=ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);int len=b.getInt(),v=b.getInt(),type=b.getInt(),tag=b.getInt();if(len<16||len>1048576||v!=1||type!=8)throw new IOException("Invalid usbmuxd request");Map<String,Object> request=Plist.parse(new String(readExactly(input,len-16),StandardCharsets.UTF_8));String action=(String)request.get("MessageType");
                if("Connect".equals(action)) {
                    int encoded=((Number)request.get("PortNumber")).intValue();int destination=((encoded&255)<<8)|((encoded>>>8)&255);
                    if(!Integer.valueOf(1).equals(((Number)request.get("DeviceID")).intValue()))throw new IOException("Unknown device");
                    channel=new Channel(nextPort.getAndIncrement(),destination);channels.put(channel.source,channel);
                    try {channel.open();}catch(Exception e){reply(output,tag,map("MessageType","Result","Number",3));throw e;}
                    reply(output,tag,map("MessageType","Result","Number",0));client.setSoTimeout(0);Channel connected=channel;
                    workers.execute(()->{try{byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)connected.write(Arrays.copyOf(buffer,n));}catch(Exception ignored){}finally{connected.close();try{client.close();}catch(IOException ignored){}}});
                    while(live&&!connected.closed){byte[] data=connected.queue.poll(1,TimeUnit.SECONDS);if(data!=null){output.write(data);output.flush();}}return;
                }
                Map<String,Object> response;
                switch(action){
                    case "ReadBUID": response=map("BUID",buid);break;
                    case "ListDevices": response=map("DeviceList",Arrays.asList(attached()));break;
                    case "Listen": reply(output,tag,map("MessageType","Result","Number",0));reply(output,0,attached());continue;
                    case "ReadPairRecord": {File f=pairFile(request);response=f.exists()?map("PairRecordData",readFile(f)):map("MessageType","Result","Number",2);break;}
                    case "SavePairRecord": {File f=pairFile(request);byte[] record=(byte[])request.get("PairRecordData");if(record==null||record.length>262144)throw new IOException("Invalid pair record");File temp=new File(f.getPath()+".tmp");try(FileOutputStream o=new FileOutputStream(temp)){o.write(record);o.getFD().sync();}if(!temp.renameTo(f))throw new IOException("Pair record save failed");response=map("MessageType","Result","Number",0);break;}
                    case "DeletePairRecord": pairFile(request).delete();response=map("MessageType","Result","Number",0);break;
                    default:response=map("MessageType","Result","Number",1);
                }
                reply(output,tag,response);
            }
        }catch(Exception error){android.util.Log.d("CarrierSIM", "Mux client: "+error.getClass().getSimpleName()+": "+error.getMessage());}finally{if(channel!=null)channel.close();clients.remove(client);try{client.close();}catch(IOException ignored){}}
    }
    private File pairFile(Map<String,Object> request) throws IOException {String id=(String)request.get("PairRecordID");if(id==null||!id.matches("[A-Za-z0-9-]{8,80}"))throw new IOException("Invalid pairing identifier");File dir=new File(context.getFilesDir(),"pair_records");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Pair folder unavailable");return new File(dir,id+".plist");}
    private static byte[] readFile(File f)throws IOException {try(FileInputStream in=new FileInputStream(f)){if(f.length()>262144)throw new IOException("Pair record too large");return readExactly(in,(int)f.length());}}
    private static void reply(OutputStream out,int tag,Map<String,Object> dict)throws IOException {byte[] xml=xml(dict).getBytes(StandardCharsets.UTF_8);out.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(xml.length+16).putInt(1).putInt(8).putInt(tag).array());out.write(xml);out.flush();}
    private static String xml(Object value) {return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\">"+element(value)+"</plist>";}
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private static String element(Object o){if(o instanceof Map){StringBuilder b=new StringBuilder("<dict>");for(Object key:((Map<?,?>)o).keySet())b.append("<key>").append(escape((String)key)).append("</key>").append(element(((Map<?,?>)o).get(key)));return b.append("</dict>").toString();}if(o instanceof List){StringBuilder b=new StringBuilder("<array>");for(Object v:(List<?>)o)b.append(element(v));return b.append("</array>").toString();}if(o instanceof byte[])return "<data>"+Base64.encodeToString((byte[])o,Base64.NO_WRAP)+"</data>";if(o instanceof Number)return "<integer>"+o+"</integer>";if(o instanceof Boolean)return (Boolean)o?"<true/>":"<false/>";return "<string>"+escape(String.valueOf(o))+"</string>";}
    private static Map<String,Object> map(Object... fields){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<fields.length;i+=2)m.put((String)fields[i],fields[i+1]);return m;}
    private static byte[] readExactly(InputStream in,int size)throws IOException {byte[] data=new byte[size];int pos=0;while(pos<size){int n=in.read(data,pos,size-pos);if(n<0)throw new EOFException();pos+=n;}return data;}
    private static byte[] join(byte[] a,byte[] b){byte[] r=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,r,a.length,b.length);return r;}
    private final class Channel {
        final int source,destination;int seq,ack,peerAck,window=65536;volatile boolean established,closed;
        final BlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(256);
        Channel(int s,int d){source=s;destination=d;}
        synchronized void open()throws Exception {send(2,new byte[0]);long until=SystemClock.elapsedRealtime()+10000;while(!established&&!closed){long left=until-SystemClock.elapsedRealtime();if(left<=0)throw new IOException("iPhone не открыл порт "+destination);wait(left);}if(closed)throw new IOException("iPhone отклонил порт "+destination);}
        synchronized void send(int flags,byte[] data)throws IOException {ByteBuffer b=ByteBuffer.allocate(20+data.length);b.putShort((short)source).putShort((short)destination).putInt(seq).putInt(ack).put((byte)0x50).put((byte)flags).putShort((short)512).putInt(0).put(data);sendMux(6,b.array());seq+=data.length;}
        synchronized void write(byte[] data)throws Exception {long until=SystemClock.elapsedRealtime()+30000;while(!closed&&((long)seq-peerAck+data.length)>window){long left=until-SystemClock.elapsedRealtime();if(left<=0)throw new IOException("USB TCP window timeout");wait(left);}if(closed)throw new IOException("USB channel closed");send(16,data);}
        synchronized void receive(byte[] p,int offset)throws IOException {ByteBuffer b=ByteBuffer.wrap(p);int flags=p[offset+13]&255;if((flags&5)!=0){close();return;}peerAck=b.getInt(offset+8);window=(b.getShort(offset+14)&65535)<<8;int rx=b.getInt(offset+4);
            if(!established){if((flags&18)==18){seq=1;ack=rx+1;established=true;send(16,new byte[0]);notifyAll();}return;}
            int header=(p[offset+12]>>>4&15)*4;if(header<20||offset+header>p.length)throw new IOException("Invalid TCP header");byte[] data=Arrays.copyOfRange(p,offset+header,p.length);
            if(data.length>0){if(rx==ack){if(!queue.offer(data))throw new IOException("USB TCP queue overflow");ack+=data.length;}else if(Integer.compareUnsigned(rx,ack)>0)throw new IOException("USB TCP sequence gap");send(16,new byte[0]);}notifyAll();}
        synchronized void close(){if(closed)return;closed=true;channels.remove(source);if(established&&live){try{send(4,new byte[0]);}catch(IOException ignored){}}notifyAll();}
    }
    @Override public void close(){if(!closed.compareAndSet(false,true))return;live=false;for(Channel c:channels.values())c.close();for(LocalSocket s:clients)try{s.close();}catch(IOException ignored){}if(server!=null)try{server.close();}catch(IOException ignored){}if(boundSocket!=null)try{boundSocket.close();}catch(IOException ignored){}if(address!=null)new File(address).delete();if(usb!=null){if(iface!=null)usb.releaseInterface(iface);usb.close();usb=null;}workers.shutdownNow();}
}
