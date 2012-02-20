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

import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import org.apache.coyote.http11.upgrade.UpgradeInbound;
import org.apache.coyote.http11.upgrade.UpgradeOutbound;
import org.apache.coyote.http11.upgrade.UpgradeProcessor;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.catalina.websocket.WebSocketFrame.OpCode;
import org.apache.catalina.websocket.WebSocketFrame.StatusCode;

public abstract class WebSocketConnection implements UpgradeInbound {

    // TODO much better fragmentation system
    private boolean currentlyFragmented = false;
    private OpCode currentDataOpcode = null;

    private UpgradeProcessor<?> processor = null;
    private UpgradeOutbound outbound;

    @Override
    public void setUpgradeOutbound(UpgradeOutbound upgradeOutbound) {
        outbound = upgradeOutbound;
    }

    @Override
    public void setUpgradeProcessor(UpgradeProcessor<?> processor) {
        this.processor = processor;
    }
    
    private class WebSocketClosedException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    @Override
    public SocketState onData() throws IOException {
	
	try {
            // Must be the start of a frame
            WebSocketFrame frame = WebSocketFrame.decode(processor);
    
            // Fragmentation
            if (currentlyFragmented) {
                // This frame is inside a fragmented message
                
                // Reject non-continuation data frames
                if(frame.isData()) {
                    writeFrame(WebSocketFrame.makeCloseFrame(StatusCode.ProtocolError));
                    closeImmediately();
                }
            } else {
                // This frame is the first frame of a new message
                
                // Reject spurious continuation frames
                if (frame.getOpcode() == OpCode.Continuation) {
                    writeFrame(WebSocketFrame.makeCloseFrame(StatusCode.ProtocolError));
                    closeImmediately();
                }
            }
            
            // Route the frame
            if (frame.isData() || frame.getOpcode() == OpCode.Continuation) {
        	
                handleDataFrame(frame);
                
                // Update fragmentation state for next time
                if(frame.isFin()) {
                    currentlyFragmented = false;
                } else if(currentlyFragmented == false) {
                    currentlyFragmented = true;
                    currentDataOpcode = frame.getOpcode();
                }
            } else if (frame.isControl()) {
                handleControl(frame);
            }
            
	} catch(CharacterCodingException e) {
	    // Payload contained invalid character data
	    writeFrame(WebSocketFrame.makeCloseFrame(StatusCode.InvalidData));
	    
	} catch(WebSocketClosedException c) {
	    // This tells the protocol above to drop the TsCP
	    return SocketState.CLOSED;
	}
        // TODO per-frame extension handling is not currently supported.

        return SocketState.UPGRADED;
    }
    
    private void handleControl(WebSocketFrame frame) throws IOException {
        // Control frames must not be fragmented
        if (frame.isFin() == false) {
            writeFrame(WebSocketFrame.makeCloseFrame(StatusCode.ProtocolError));
            closeImmediately();
        }

        // Control frames must not have extended length
        if (frame.getPayloadLength() > 125) {
            writeFrame(WebSocketFrame.makeCloseFrame(StatusCode.ProtocolError));
            closeImmediately();
        }

        switch (frame.getOpcode()) {
        case Ping:
            System.out.println("<ping />");
            writeFrame(WebSocketFrame.makePong(frame));
            break;
        case Pong:
            System.out.println("<pong />");
            swallowFrame(frame);
            break;
        case ConnectionClose:
            // Analyze the closing frame
            analyzeIncomingClose(frame);
            
            // Reply with a close
            writeFrame(WebSocketFrame.closeFrame());
            closeImmediately();
            break;
        }
    }
    
    /**
     * Reads the payload of the given frame, and ignores it
     * @param WebSocketFrame the frame to swallow
     * @throws IOException 
     */
    private void swallowFrame(WebSocketFrame frame) throws IOException {
        // Grab the frame's payload
        InputStream payload = frame.getPayload();

        // Swallow the stream
        while (payload.read() >= 0);
    }

    private void analyzeIncomingClose(WebSocketFrame close) throws IOException {
        // TODO Optionally deal with abnormal status codes
        // (probably this would be done in WebSocketFrame)

        // Close payloads must be empty, or contain status
        // information which is at least two bytes long
        if (close.getPayloadLength() == 1) {
            writeFrame(WebSocketFrame.makeCloseFrame(StatusCode.ProtocolError));
            closeImmediately();
        }
        
        Long statusCode = close.decodeStatusCode();
        if (statusCode != null) {
            if (statusCode < 1000 || statusCode > 4999 || statusCode == 1004) {
                throw new WebSocketClosedException();
            }
        }
    }

    private void closeImmediately() throws IOException {
        throw new WebSocketClosedException();
    }

    public void writeFrame(WebSocketFrame frame) throws IOException {
        frame.encode(outbound);
        outbound.flush();
    }

    private void handleDataFrame(WebSocketFrame frame) throws IOException {
        OpCode opcode = frame.getOpcode();
        
        if(opcode == OpCode.Continuation) {
            opcode = currentDataOpcode;
        }
        
        switch (opcode) {
        case Text:
            onTextData(frame);
            break;

        case Binary:
            onBinaryData(frame);
            break;
        }
    }

    protected abstract void onTextData(WebSocketFrame frame) throws IOException;

    protected abstract void onBinaryData(WebSocketFrame frame)
            throws IOException;

    protected abstract void endOfMessage();
}
