package com.fpnn.rtausdk;

/***********需要用户重载的push消息类(如果有耗时操作 需要用户单开线程处理业务逻辑 以免阻塞后续的请求)************/
public class RTAUPushProcessor
{
    int internalReloginMaxTimes = 99;


    /**
     * RTAU链接断开 (默认会自动连接)
     */
    public void rtauConnectClose(){}

    /**
     * RTAU重连开始接口 每次重连都会判断reloginWillStart返回值 若返回false则中断重连
     * 参数说明 uid-用户id  answer-上次重连的结果  reloginCount-将要重连的次数
     * 备注:需要用户设定一些条件 比如重连间隔 最大重连次数
     */
    public boolean reloginWillStart(int reloginCount){return true;}

    /**
     * RTAU重连完成(如果 successful 为false表示最终重连失败,answer会有详细的错误码和错和错误信息 为true表示重连成功)
     */
    public void reloginCompleted(boolean successful, RTAUStruct.RTAUAnswer answer, int reloginCount){}
}


