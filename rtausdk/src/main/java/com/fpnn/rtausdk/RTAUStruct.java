package com.fpnn.rtausdk;

public class RTAUStruct {
    public static class RTAUAnswer
    {
        public int errorCode = -1;
        public String errorMsg = "";
        public RTAUAnswer(){}
        public RTAUAnswer(int _code, String msg){
            errorCode = _code;
            errorMsg = msg;
        }
        public String getErrInfo(){
            return  " " + errorCode + "-" + errorMsg;
        }
    }

}
