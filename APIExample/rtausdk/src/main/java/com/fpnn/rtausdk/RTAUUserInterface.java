package com.fpnn.rtausdk;

public class RTAUUserInterface {

    //返回RTAUAnswer的回调接口
    public interface IRTAUEmptyCallback {
        void onResult(RTAUStruct.RTAUAnswer answer);
    }

    //泛型接口 带有一个返回值的回调函数 (请优先判断answer的错误码 泛型值有可能为null)
    public interface IRTAUCallback<T> {
        void onResult(T t, RTAUStruct.RTAUAnswer answer);
    }

    //泛型接口 带有两个返回值的回调函数 (请优先判断answer的错误码, 泛型值有可能为null)
    public interface IRTAUDoubleValueCallback<T,V> {
        void onResult(T t, V v, RTAUStruct.RTAUAnswer answer);
    }
}
