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

import org.apache.coyote.http11.upgrade.UpgradeInbound;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.catalina.websocket.WebSocketFrame.OpCode;
import org.apache.catalina.websocket.WebSocketFrame.StatusCode;

public abstract class WebSocketConnection extends UpgradeInbound {
    /**
     * The possible connection states (matches W3C client API)
     */
    public static enum WebSocketState {
        /**
         * Connection not yet established
         */
        CONNECTING,
        
        /**
         * Bidirectional communication is possible
         */
        OPEN,
        
        /**
         * Connection is going through the closing handshake
         */
        CLOSING,
        
        /**
         * Connection closed
         */
        CLOSED;
    }
    
    /**
     * The current connection state (matches W3C client API)
     */
    private WebSocketState readyState = WebSocketState.CONNECTING;

    /**
     * Flag indicating the connection has received the first fragment of a
     * fragmented message but NOT its final fragment.
     */
    private boolean currentlyFragmented = false;
    // TODO Buffering fragmentation system

    /**
     * Sends the given frame over this connection (may or may not fragment)
     * 
     * @param WebSocketFrame
     *            the frame to send
     * @returns true if the frame was sent (false if the connection
     *              was not open, in which case the frame is swallowed)
     * @throws IOException
     */
    public boolean send(WebSocketFrame frame) throws IOException {
        // Don't send unless the connection is open
        if(readyState != WebSocketState.OPEN) {
            swallowFrame(frame);
            return false;
        }
        
        // All server-to-client messages must not be masked
        if (frame.isMask()) {
            frame.toggleMask();
        }

        // Write to the connector
        frame.encode(outbound);
        outbound.flush();
        return true;
    }
    
    /**
     * Initiates the closing handshake
     * @throws IOException
     */
    public void close() throws IOException {
        // Have we even finished connecting?
        if(readyState == WebSocketState.CONNECTING) {
            readyState = WebSocketState.CLOSED;
            throw new WebSocketClosedException();
        }
        
        // Only close if we're open
        if(readyState != WebSocketState.OPEN) {
            return;
        }

        // Send the closing handshake
        send(WebSocketFrame.makeCloseFrame(StatusCode.NormalClose));
        readyState = WebSocketState.CLOSING;
    }

    /**
     * Called when a message (or message fragment) is received
     * 
     * @param WebSocketFrame
     *            the received frame
     * @throws IOException
     */
    protected abstract void onMessage(WebSocketFrame frame) throws IOException;

    /**
     * Called after the final fragment of a message is received (subclasses may
     * override this method)
     */
    protected void onFinalFragment() {
        // Subclasses may override this method
    }

    /**
     * Called when the connection is fully open (subclasses may override this
     * method)
     */
    protected void onOpen() {
        // Subclasses may override this method
    }

    /**
     * Called when the connection is closed
     * (subclasses may override this method)
     */
    protected void onClose() {
        // Subclasses may override this method
    }

    /**
     * Called when the connection is closed due to error (subclasses may
     * override this method)
     */
    protected void onError() {
        // Subclasses may override this method
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
                if (frame.isData()) {
                    send(WebSocketFrame
                            .makeCloseFrame(StatusCode.ProtocolError));
                    onError();
                    closeImmediately();
                }
            } else {
                // This frame is the first frame of a new message

                // Reject spurious continuation frames
                if (frame.getOpcode() == OpCode.Continuation) {
                    send(WebSocketFrame
                            .makeCloseFrame(StatusCode.ProtocolError));
                    onError();
                    closeImmediately();
                }
            }

            // Route the frame
            if (frame.isData() || frame.getOpcode() == OpCode.Continuation) {

                handleDataFrame(frame);

                // Update fragmentation state for next time
                if (frame.isFin()) {
                    currentlyFragmented = false;
                    onFinalFragment();
                } else if (currentlyFragmented == false) {
                    currentlyFragmented = true;
                }
            } else if (frame.isControl()) {
                handleControl(frame);
            }

        } catch (WebSocketClosedException c) {
            onClose();
            return SocketState.CLOSED;
            
        } catch (IOException e) {
            onClose();
            return SocketState.CLOSED;
            
        }

        // TODO per-frame extension handling is not currently supported.

        return SocketState.UPGRADED;
    }
    
    /**
     * @returns the current WebSocket state of this connection
     */
    public WebSocketState getReadyState() {
        return readyState;
    }

    private void handleDataFrame(WebSocketFrame frame) throws IOException {
        onMessage(frame);
    }

    private void handleControl(WebSocketFrame frame) throws IOException {
        // Control frames must not be fragmented
        if (frame.isFin() == false) {
            send(WebSocketFrame.makeCloseFrame(StatusCode.ProtocolError));
            onError();
            closeImmediately();
        }

        // Control frames must not have extended length
        if (frame.getPayloadLength() > 125) {
            send(WebSocketFrame.makeCloseFrame(StatusCode.ProtocolError));
            onError();
            closeImmediately();
        }

        switch (frame.getOpcode()) {
        case Ping:
            //System.out.println("<ping />");
            send(WebSocketFrame.makePong(frame));
            break;
        case Pong:
            //System.out.println("<pong />");
            swallowFrame(frame);
            break;
        case ConnectionClose:
            // Analyze the closing frame
            analyzeIncomingClose(frame);

            // Are we expecting a closing reply?
            if(readyState == WebSocketState.CLOSING) {
                // Handshake complete
                closeImmediately();
            }
            
            // Reply with a close
            send(WebSocketFrame.closeFrame());
            closeImmediately();
            break;
        }
    }

    /**
     * Reads the payload of the given frame, and ignores it
     * 
     * @param WebSocketFrame
     *            the frame to swallow
     * @throws IOException
     */
    protected void swallowFrame(WebSocketFrame frame) throws IOException {
        // Grab the frame's payload
        InputStream payload = frame.getPayload();

        // Swallow the stream
        while (payload.read() >= 0);
    }

    private void analyzeIncomingClose(WebSocketFrame close) throws IOException {
        // Close payloads must be empty, or contain status
        // information which is at least two bytes long
        if (close.getPayloadLength() == 1) {
            send(WebSocketFrame.makeCloseFrame(StatusCode.ProtocolError));
            onError();
            closeImmediately();
        }

        Long statusCode = close.decodeStatusCode();
        if (statusCode != null) {
            if (!WebSocketFrame.StatusCode.isValid(statusCode)) {
                //System.out.println("Close code invalid " + statusCode);
                onError();
                closeImmediately();
            }
        }
    }

    /**
     * Drops the underlying TCP connection
     * @throws IOException
     */
    private void closeImmediately() throws IOException {
        readyState = WebSocketState.CLOSED;
        throw new WebSocketClosedException();
    }

    /**
     * This allows to determine when the upgrade is complete
     */
    @Override
    public void onUpgradeComplete() {
        readyState = WebSocketState.OPEN;
        onOpen();
    }
    
    protected class WebSocketClosedException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
