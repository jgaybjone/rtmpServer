package Decoder;

import AMF.*;
import Util.Common;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.*;

public class RtmpDecoder extends ByteToMessageDecoder {

    //chunk head 数据
    private List<Byte> chunkData = new ArrayList<Byte>(); // chunk所有数据， 包含 header
    private int chunkHeadIndex = 0; //byte head 提取的下标
    private int timestamp = 0; // 时间戳
    private int msgLength = 0; //整个chunk数据长度，不包含 header
    private int allMsglength = 0;
    private byte msgType; //消息类型
    private int streamId = 0;
    private boolean isExtendedTimestamp = false;

    //  chunk message 数据
    private List<Byte> chunkMessage = new ArrayList<Byte>(); //chunk 实际数据
    private int chunkMessageIndex = 0; //byte 具体数据 获取下标
    private int MT ;
    private int payloadLength = 0;
    private int strameId = 0;


    //握手数据
    private List<Byte> handshakeData = new ArrayList<Byte>();
    private boolean isHandshake = false;
    private boolean isSendS1 = false;
    private List<Byte> S1 = new ArrayList<Byte>();
    private List<Byte> S2 = new ArrayList<Byte>();
    private byte[] zero = {0x00,0x00,0x00,0x00};
    @Override


    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int length = in.readableBytes();
        byte[] data = new byte[length];
        in.readBytes(data);
        if(!isHandshake && handshakeData.size() <= Common.HANDSHAKE_LENGTH) { //rtmp 握手认证
            int len = Common.HANDSHAKE_LENGTH - handshakeData.size(); //剩下的数据长度
            if(data.length >= len) { //把剩下的长度拿掉
                for(int i = 0; i < len;i++) {
                    handshakeData.add(data[i]);
                }
                for(int i = len;i < data.length;i++) { //将剩下的数据添加到chunkData
                    chunkData.add(data[i]);
                }
            } else {
                for(byte i: data) {
                    handshakeData.add(i); //握手数据添加进来
                }
            }
            handshake(ctx);
        } else {
            for(byte i: data){
                chunkData.add(i);
            }
            System.out.println("=========================allChunkData=================================");
            System.out.println(Common.bytes2hex(Common.conversionByteArray(chunkData)));
            System.out.println("==========================================================");

            handChunkMessage(chunkData,ctx);

        }
    }

    /**
     * 解析chunkMessage 数据
     * @param chunkData
     * @param ctx
     */
    private void handChunkMessage(List<Byte> chunkData,ChannelHandlerContext ctx) {
      //  System.out.println(Common.bytes2hex(Common.conversionByteArray(chunkData)));
        byte flags = chunkData.get(0);
        int[]  chunk_head_length = {12,8,4,1}; //对应的 chunk head length 长度
       // byte fmtByte =  (byte)((byte)flags >> 6); //向右移动 6 位 获取 fmt
        int fmt =  (byte) ((flags & 0xff & 0xff) >> 6);
        System.out.println("fmt === " +fmt);
        int csidTS = (byte)((flags & 0xff & 0xff) & 0x3f); // 按位与 11 为 1 ，有0 为 0
        System.out.println("flags" + Integer.toHexString((byte)(flags & 0xff & 0xff)));
        System.out.println("csidts ===" +  csidTS);
        try{
             int head_len = chunk_head_length[fmt];
            int basic_head_len = chunkHeadIndex = getBasicHeadLength(csidTS);
            byte[] chunkDataByte = Common.conversionByteArray(chunkData);

            if(head_len >= 4) { // 大于 1 先提取出 timestamp
                byte[] timestampByte = new byte[Common.TIMESTAMP_BYTE_LENGTH];
                System.arraycopy(chunkDataByte,chunkHeadIndex,timestampByte,0,Common.TIMESTAMP_BYTE_LENGTH);
                timestamp =  Common.byteToInt24(timestampByte);
                if(timestamp == Common.TIMESTAMP_MAX_NUM) {
                    isExtendedTimestamp = true; // 前3个字节放不下，放在最后面的四个字节
                }
                System.out.println("timestamp == " + timestamp);
                chunkHeadIndex = chunkHeadIndex + Common.TIMESTAMP_BYTE_LENGTH;
            }
            if(head_len >= 8) { // 大于 4 先提取出 msgLength
                byte[] msg_len = new byte[Common.TIMESTAMP_BYTE_LENGTH];
                System.arraycopy(chunkDataByte,chunkHeadIndex,msg_len,0,Common.MSG_LEN_LENGTH);
                this.msgLength = Common.byteToInt24(msg_len);
                this.allMsglength = Common.byteToInt24(msg_len);
                if(this.msgLength > chunkData.size()) { //后面分包的情况,这方法可能有问题
                    System.out.println("101 数据不全");
                    //System.out.println(Common.bytes2hex(Common.conversionByteArray(chunkData)));
                    //ctx.close();
                }
                System.out.println("msg len " + this.msgLength);
                chunkHeadIndex = chunkHeadIndex + Common.TIMESTAMP_BYTE_LENGTH;

                this.msgType = chunkDataByte[chunkHeadIndex];
                chunkHeadIndex = chunkHeadIndex + Common.MST_TYPE_LENGTH;
                System.out.println("消息类型 === " + Integer.toHexString(this.msgType));
            }

            if(head_len >= 12) {
                byte[] streamByte = new byte[Common.STREAM_ID_LENGTH];
                System.arraycopy(chunkDataByte,chunkHeadIndex,streamByte,0,Common.STREAM_ID_LENGTH);
                this.streamId = Common.byteSmallToInt(streamByte); //只有 stream 是小端模式
                System.out.println("streamId === " + streamId);
                chunkHeadIndex = chunkHeadIndex + Common.STREAM_ID_LENGTH;
            }
            if(isExtendedTimestamp) {
                byte[] timestampByte = new byte[Common.EXTEND_TIMESTAMP_LENGTH];
                System.arraycopy(chunkDataByte,chunkHeadIndex,timestampByte,0,Common.EXTEND_TIMESTAMP_LENGTH);
                this.timestamp = Common.byteToInt24(timestampByte);
                chunkHeadIndex = chunkHeadIndex + Common.EXTEND_TIMESTAMP_LENGTH;
            }
            int msgIndex = msgLength > Common.DEFAULT_CHUNK_MESSAGE_LENGTH ? chunkHeadIndex + Common.DEFAULT_CHUNK_MESSAGE_LENGTH : chunkHeadIndex + msgLength;

            for(int i = chunkHeadIndex;i < msgIndex; i++) {
                chunkMessage.add(chunkData.get(i));
            }
            System.out.println("=================message============================");
            System.out.println(Common.bytes2hex(Common.conversionByteArray(chunkMessage)));
            System.out.println("=============================================");

            System.out.println("chunkMessage size " + chunkMessage.size() + " msgLength " + msgLength + " msg index " + msgIndex);
            if(chunkMessage.size() < allMsglength){ //还没有提取完所有数据
                msgLength = allMsglength - chunkMessage.size();
            } else {
                handMessage(Common.conversionByteArray(chunkMessage),ctx); //这里还没有处理到分包的情况
                isExtendedTimestamp = false;
                chunkMessage = new ArrayList<Byte>();
                System.out.println(msgIndex);
            }
            chunkHeadIndex = 0;
            chunkData = Common.removeList(chunkData,0,msgIndex - 1); // 如果chunkData 还有数据，粘包了，那么解析就好了
            System.out.println(chunkData.size());
            if(chunkData.size() > 0) { //如果还有数据，那么继续解析就好了
                handChunkMessage(chunkData,ctx);
            }
            System.out.println("=================chunkdata============================");
            System.out.println(Common.bytes2hex(Common.conversionByteArray(chunkData)));
            System.out.println("=============================================");
        }catch (Exception e) {
            ctx.close();
            e.printStackTrace();
            System.out.println(Common.bytes2hex(Common.conversionByteArray(chunkData)));
        }

    }

    private void handMessage(byte[] message,ChannelHandlerContext ctx) {
        AMFClass amfClass = new AMFClass();
        amfClass.message = message;
        amfClass.pos = 0;
        switch (msgType) {
            case 0x14:
                System.out.println("消息控制服务");
                String msg = AMFUtil.load_amf_string(amfClass);
                double txid = AMFUtil.load_amf_number(amfClass);
                System.out.println(msg);
                if(msg.equals("connect")) {
                    Map<String,Object> data = AMFUtil.load_amf_object(amfClass);
                    if(data.containsKey("app")) {
                        String app = data.get("app").toString();
                        if(app.equals(Common.APP_NAME)) {
                            List<Byte> result = new ArrayList<Byte>();
                            byte[] resultString = AMFUtil.writeString("_result");
                            for(byte i: resultString){
                                result.add(i);
                            }
                            byte[] resultNumber = AMFUtil.writeNumber(txid);
                            for(byte i: resultNumber){
                                result.add(i);
                            }
                            Map<String,Object> version = new HashMap<String, Object>();
                            double capabilities = 255.0;
                            double mode = 1.0;
                            version.put("fmsVer","FMS/4,5,1,484");
                            version.put("capabilities",capabilities);
                            version.put("mode",mode);
                            byte[] versionByte = AMFUtil.writeObject(version);
                            for(byte i: versionByte){
                                result.add(i);
                            }
                            Map<String,Object> status = new HashMap<String, Object>();
                            double objectEncoding = 3.0;
                            status.put("level","status");
                            status.put("code","NetConnection.Connect.Success");
                            status.put("description","Connection succeeded.");
                            status.put("objectEncoding",objectEncoding);
                            byte[] statusVersion = AMFUtil.writeObject(status);
                            //System.out.println(Common.bytes2hex(statusVersion));
                            for(byte i: statusVersion){
                                result.add(i);
                            }
                            sendData(result,ctx);
                        }
                    }
                } else if(msg.equals("createStream")) {
                    List<Byte> result = new ArrayList<Byte>();
                    byte[] resultString = AMFUtil.writeString("_result");
                    for(byte i: resultString){
                        result.add(i);
                    }
                    byte[] resultNumber = AMFUtil.writeNumber(txid);
                    for(byte i: resultNumber){
                        result.add(i);
                    }
                    byte[] resultStream = AMFUtil.writeNumber(Common.STREAM_ID);
                    for(byte i: resultStream){
                        result.add(i);
                    }
                    sendData(result,ctx);
                } else if(msg.equals("publish")) {
                   AMFUtil.load_amf(amfClass);
                   String path = AMFUtil.load_amf_string(amfClass); //这个为发布的 url 协议
                   System.out.println(path);
                   Map<String,Object> status = new HashMap<String, Object>();
                   status.put("level","status");
                   status.put("code","NetStream.Publish.Start");
                   status.put("description","Stream is now published.");
                   status.put("details",path);

                   List<Byte> result = new ArrayList<Byte>();
                   for(byte i: AMFUtil.writeString("onStatus")) {
                       result.add(i);
                   }
                   for(byte i: AMFUtil.writeNumber(0.0)){
                       result.add(i);
                   }
                   result.add(AMFUtil.writeNull());
                   for(byte i : AMFUtil.writeObject(status)){
                       result.add(i);
                   }
                   sendData(result,ctx);

                    List<Byte> result2 = new ArrayList<Byte>();
                    byte[] resultString = AMFUtil.writeString("_result");
                    for(byte i: resultString){
                        result2.add(i);
                    }
                    byte[] resultNumber = AMFUtil.writeNumber(txid);
                    for(byte i: resultNumber){
                        result2.add(i);
                    }
                    sendData(result2,ctx);
                }
                break;
             default:
                 break;
        }
//       MT =  message[chunkMessageIndex];
//       System.out.println("Mt === " + MT  );
//       chunkMessageIndex = chunkMessageIndex + Common.MESSAGE_MT_LENGTH;
//       byte[] payLoadByte = new byte[Common.MESSAGE_PAYLOAD_LENGTH];
//       System.arraycopy(message,chunkMessageIndex,payLoadByte,0,Common.MESSAGE_PAYLOAD_LENGTH);
//       chunkMessageIndex = chunkMessageIndex + Common.MESSAGE_PAYLOAD_LENGTH;
//       this.payloadLength = Common.byteToInt24(payLoadByte);
//       System.out.println("payloadLength === " + this.payloadLength );
    }

    private void sendData(List<Byte> chunkData,ChannelHandlerContext ctx) {
        List<Byte> rtmpHead = new ArrayList<Byte>();
        byte flags = (3 & 0x3f) | (0 << 6);
        rtmpHead.add(flags);
        byte[] timestamp = {0x00,0x00,0x00};
        for(byte i: timestamp){
            rtmpHead.add(i);
        }
        int msg_len = chunkData.size();
        byte[] msgLength = Common.intToByte(msg_len);
        rtmpHead.add(msgLength[2]);
        rtmpHead.add(msgLength[1]);
        rtmpHead.add(msgLength[0]);
        byte msg_type = 0x14;
        rtmpHead.add(msg_type);
        rtmpHead.add((byte) 0x00);
        rtmpHead.add((byte) 0x00);
        rtmpHead.add((byte) 0x00);
        rtmpHead.add((byte) 0x00);
        List<Byte> chunk = new ArrayList<Byte>();
        for(int i = 0; i < rtmpHead.size(); i++){
            chunk.add(rtmpHead.get(i));
        }
        int pos = 0;
        while(pos < chunkData.size()){
            if(chunkData.size() - pos < Common.DEFAULT_CHUNK_MESSAGE_LENGTH){
                for(int i = pos; i < chunkData.size();i++){
                    chunk.add(chunkData.get(i));
                }
            } else {
                for(int i = pos; i < pos + 128;i++) {
                    chunk.add(chunkData.get(i));
                }
                chunk.add((byte) ((3 & 0x3f) | (3 << 6)));
            }
            pos += Common.DEFAULT_CHUNK_MESSAGE_LENGTH;
        }
        ctx.writeAndFlush(Unpooled.copiedBuffer(Common.conversionByteArray(chunk)));
    }


    private int getBasicHeadLength(int csidTs) {
        if(csidTs == 0) {
            return 2;
        }
        if(csidTs == 0x3f) {
            return 3;
        }
        return 1;
    }


    /**
     * rtmp 握手数据判断
     * @param ctx
     */
    private void handshake(ChannelHandlerContext ctx){
        if(handshakeData.size() >= Common.C0_LENGTH && !isSendS1){
            byte c0 = handshakeData.get(Common.C0_INDEX);
            if(c0 != Common.C0){ //如果 c0 错误，那么关闭连接
                ctx.close();
                return;
            } else {
                int time = (int) (new Date().getTime() / 1000);
                byte[] timeByte = Common.intToByte(time);
                ctx.writeAndFlush(Unpooled.copiedBuffer(new byte[]{Common.S0}));
                for(byte i : timeByte){
                    S1.add(i);
                }
                for(byte i: zero){
                    S1.add(i);
                }
                for(int i = 0; i < Common.RANDOM_LENGTH;i++) {
                    Random random = new Random();
                    S1.add((byte) random.nextInt(9));
                }
                ctx.writeAndFlush(Unpooled.copiedBuffer(Common.conversionByteArray(S1)));
                isSendS1 = true;
            }
        }

        if(handshakeData.size() >= (Common.C0_LENGTH + Common.C1_LENGTH)){ // 服务端接收 c1 完毕，开始发送s2
            for(int i = 1;i <= 4; i++) {  //提取 c1 的 time
                S2.add(handshakeData.get(i));
            }
            int time = (int) (new Date().getTime() / 1000); //设置 s2 time
            byte[] timeByte = Common.intToByte(time);
            for(byte i : timeByte){
                S2.add(i);
            }
            for(int i = 8; i < Common.C1_LENGTH;i++) {
                S2.add(handshakeData.get(i));
            }
            ctx.writeAndFlush(Unpooled.copiedBuffer(Common.conversionByteArray(S2)));
        }
        if(handshakeData.size() >= Common.HANDSHAKE_LENGTH) {
            isHandshake = true;
//            System.out.println("S1");
//            byte[] s1 = Common.conversionByteArray(S1);
//            System.out.println(s1.length);
//            System.out.println(Common.bytes2hex(s1));;
////            byte[] s2 = Common.conversionByteArray(S2);
////            System.out.println("S2");
////            System.out.println(Common.bytes2hex(s2));
//
//            List<Byte> c1List = new ArrayList<Byte>();
//            List<Byte> c2List = new ArrayList<Byte>();
//
//            for(int i = 1;i < 1537;i++ ) {
//                c1List.add(handshakeData.get(i));
//            }
//            for(int i = 1537;i < handshakeData.size();i++) {
//                c2List.add(handshakeData.get(i));
//            }

//            System.out.println("接受到的握手数据");
//            System.out.println(handshakeData.size());
//            System.out.println("C1");
//            System.out.println(Common.bytes2hex(Common.conversionByteArray(c1List)));
//            System.out.println("C2");
//            System.out.println(c2List.size());
//            System.out.println(Common.bytes2hex(Common.conversionByteArray(c2List)));
//
//            System.out.println(Common.bytes2hex(Common.conversionByteArray(handshakeData)));

        }
    }
}
