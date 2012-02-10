/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.websocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

import org.apache.catalina.util.Conversions;
import org.apache.coyote.http11.upgrade.UpgradeInbound;
import org.apache.coyote.http11.upgrade.UpgradeOutbound;
import org.apache.coyote.http11.upgrade.UpgradeProcessor;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.catalina.websocket.WebSocketFrame.OpCode;

public abstract class StreamInbound implements UpgradeInbound {

    // These attributes apply to the current frame being processed
//    private boolean fin = true;
//    private boolean rsv1 = false;
//    private boolean rsv2 = false;
//    private boolean rsv3 = false;
//    private int opCode = -1;
//    private long payloadLength = -1;

    // These attributes apply to the message that may be spread over multiple
    // frames
    private boolean continued = false;
    private OpCode messageDataType;

    private UpgradeProcessor<?> processor = null;
    private WsOutbound outbound;

    @Override
    public void setUpgradeOutbound(UpgradeOutbound upgradeOutbound) {
        outbound = new WsOutbound(upgradeOutbound);
    }


    @Override
    public void setUpgradeProcessor(UpgradeProcessor<?> processor) {
        this.processor = processor;
    }

    public WsOutbound getStreamOutbound() {
        return outbound;
    }

    @Override
    public SocketState onData() throws IOException {
        // Must be the start of a frame
    	
    	
    	WebSocketFrame frame = WebSocketFrame.decode(processor);
	
    	if (!continued){
//        	beginning frame of a message: allowed combinations
//    		Text/Binary + Fin1/Fin0
//			Control + Fin1
    		
    		if (frame.getOpcode()==OpCode.Continuation){
//    			TODO: protocol error
    		}
    		if (frame.isControl() && frame.isFin()){
//    			TODO: protocol error    			
    		}
    		
    	} else {
//        	middle frame of a message: allowed combinations
//    		Continuation + Fin0
//    		Continuation + Fin1
//    		Control + Fin0 (inserted inside, if not, previous frame would have Fin1)
    		switch(frame.getOpcode()){
    			case Ping:
    			case Pong:
    			case ConnectionClose:
    				if (frame.isFin()){
//    	    			TODO: protocol error
    				}
    				break;
    			case Text:
    			case Binary:
//    				TODO: protocol error
    				break;
    			case Continuation:
    				frame.setOpcode(messageDataType);
    				break;
    		}
    		
    	}
		setContinued(frame);
		if(frame.isData()){
			handleDataFrame(frame);
		}else if(frame.isControl()){
			handleControl(frame);
		}


        // TODO: Per frame extension handling is not currently supported.



        return SocketState.UPGRADED;
    }

    
    
    protected abstract void onBinaryData(InputStream is) throws IOException;
    protected abstract void onTextData(Reader r) throws IOException;
    protected abstract void endOfMessage();
    
    private void setContinued(WebSocketFrame frame){
		if(frame.isFin()){
			continued = false;
		}else{
			continued = true;
		}
    }
    private void handleControl(WebSocketFrame frame){
//    	TODO: handle control frames
    	
    	switch(frame.getOpcode()){
    	case Ping:
    	case Pong:
    	case ConnectionClose:
    		
    	}
    }
    private void handleDataFrame(WebSocketFrame frame) throws IOException {
      WsInputStream wsIs = new WsInputStream(processor, frame.getMaskingKey(),
    		  frame.getPayloadLength());
    	switch(frame.getOpcode()){
			case Text:
		        InputStreamReader r =
		        	new InputStreamReader(wsIs, B2CConverter.UTF_8);
		        onTextData(r);
			case Binary:

				onBinaryData(wsIs);
    	}
    	if (!continued){
    		endOfMessage();
    	}else{
    		messageDataType = frame.getOpcode();
    	}
    	
    }
    
    private void validateOpCode(int opCode) throws IOException {
        switch (opCode) {
        case 0:
        case 1:
        case 2:
        case 8:
        case 9:
        case 10:
            break;
        default:
            // TODO: Message
            throw new IOException();
        }
    }
}
