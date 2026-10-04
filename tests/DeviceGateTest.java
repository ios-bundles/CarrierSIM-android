package ru.carriersim.android;
public class DeviceGateTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        check(DeviceGate.canOperate(7,7,true,false,true,2));
        check(!DeviceGate.canOperate(-1,7,true,false,true,2)); // cable detached, stale state
        check(!DeviceGate.canOperate(8,7,true,false,true,2)); // another phone
        check(!DeviceGate.canOperate(7,7,false,false,true,2)); // trust not established
        check(!DeviceGate.canOperate(7,7,true,true,true,2)); // operation running
        check(!DeviceGate.canOperate(7,7,true,false,false,0)); // Wi-Fi iPad
        check(!DeviceGate.canOperate(7,7,true,false,true,0)); // no active SIM
        System.out.println("Device gate: 7 checks passed");
    }
}
