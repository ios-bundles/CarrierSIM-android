package ru.carriersim.android;

import android.util.Xml;
import android.util.Base64;
import org.xmlpull.v1.XmlPullParser;
import java.io.StringReader;
import java.io.IOException;
import java.util.*;

/** Parse XML plist responses without resolving external DTDs. */
final class Plist {
    static Map<String,Object> parse(String xml) throws IOException {
        try {
            XmlPullParser p=Xml.newPullParser();p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);
            p.setInput(new StringReader(xml));
            while(p.next()!=XmlPullParser.END_DOCUMENT) {
                if(p.getEventType()==XmlPullParser.START_TAG&&p.getName().equals("dict")) {
                    Object result=value(p);return (Map<String,Object>)result;
                }
            }
            throw new IOException("Ответ не содержит plist dictionary");
        } catch(IOException e) {throw e;} catch(Exception e) {throw new IOException("Некорректный plist",e);}
    }
    private static Object value(XmlPullParser p) throws Exception {
        String tag=p.getName();
        if(tag.equals("dict")) {
            Map<String,Object> result=new LinkedHashMap<>();
            while(p.nextTag()!=XmlPullParser.END_TAG) {
                if(!p.getName().equals("key"))throw new IOException("Ожидался plist key");
                String key=p.nextText();p.nextTag();result.put(key,value(p));
            }
            return result;
        }
        if(tag.equals("array")) {
            List<Object> values=new ArrayList<>();while(p.nextTag()!=XmlPullParser.END_TAG)values.add(value(p));return values;
        }
        if(tag.equals("true")||tag.equals("false")) {p.nextTag();return tag.equals("true");}
        String text=p.nextText();
        if(tag.equals("integer"))return Long.parseLong(text);
        if(tag.equals("data"))return Base64.decode(text,Base64.DEFAULT);
        return text;
    }
    static String summary(Map<String,Object> reply) {
        if(reply.containsKey("Error"))return "Недоступно: "+reply.get("Error");
        Object value=reply.get("Value");if(value==null)value=reply.get("Type");
        return value==null?reply.toString():String.valueOf(value);
    }
}
