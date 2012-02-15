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

import org.apache.coyote.http11.upgrade.UpgradeInbound;
import org.apache.coyote.http11.upgrade.UpgradeOutbound;
import org.apache.coyote.http11.upgrade.UpgradeProcessor;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.catalina.websocket.WebSocketFrame.OpCode;

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

    @Override
    public SocketState onData() throws IOException {
        // Must be the start of a frame
        WebSocketFrame frame = WebSocketFrame.decode(processor);

        // Fragmentation
        if (currentlyFragmented) {
            // This frame is inside a fragmented message
            
            // Reject non-continuation data frames
            if(frame.isData()) {
                writeFrame(WebSocketFrame.protocolErrorCloseFrame());
                closeImmediately();
            }
        } else {
            // This frame is the first frame of a new message
            
            // Reject spurious continuation frames
            if (frame.getOpcode() == OpCode.Continuation) {
                writeFrame(WebSocketFrame.protocolErrorCloseFrame());
                closeImmediately();
            }
        }
        
        // Route the frame
        if (frame.isData() || frame.getOpcode() == OpCode.Continuation) {
            handleDataFrame(frame);
            
            // Update fragmentation state for next time
            if(frame.isFin()) {
                currentlyFragmented = false;
            } else {
                currentlyFragmented = true;
                currentDataOpcode = frame.getOpcode();
            }
        } else if (frame.isControl()) {
            handleControl(frame);
        }
        
        // TODO per-frame extension handling is not currently supported.

        return SocketState.UPGRADED;
    }
    
    private void handleControl(WebSocketFrame frame) throws IOException {
        // Control frames must not be fragmented
        if (frame.isFin() == false) {
            writeFrame(WebSocketFrame.protocolErrorCloseFrame());
            closeImmediately();
        }

        // Control frames must not have extended length
        if (frame.getPayloadLength() > 125) {
            writeFrame(WebSocketFrame.protocolErrorCloseFrame());
            closeImmediately();
        }

        switch (frame.getOpcode()) {
        case Ping:
            System.out.println("<ping />");
            writeFrame(WebSocketFrame.makePong(frame));
            break;
        case Pong:
            System.out.println("<pong />");
            break;
        case ConnectionClose:
            // Reply with a close
            writeFrame(WebSocketFrame.closeFrame());
            closeImmediately();
            break;
        }
    }

    private void closeImmediately() throws IOException {
        // drop the TCP connection
        processor.close();
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
