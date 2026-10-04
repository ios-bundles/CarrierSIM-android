package ru.carriersim.android;

/** The same guard is used by screens and by the operation service. */
public final class DeviceGate {
    public static boolean ready(int attachedId,int verifiedId,boolean verified){
        return attachedId!=-1&&attachedId==verifiedId&&verified;
    }
    public static boolean canOperate(int attachedId,int verifiedId,boolean verified,boolean busy,boolean cellular,int sims){
        return ready(attachedId,verifiedId,verified)&&!busy&&cellular&&sims>0;
    }
}
