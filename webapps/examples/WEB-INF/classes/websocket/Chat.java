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
package websocket;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.apache.catalina.websocket.WebSocketConnection;
import org.apache.catalina.websocket.WebSocketFrame;
import org.apache.catalina.websocket.WebSocketServlet;

public class Chat extends WebSocketServlet {

    private static final long serialVersionUID = 1L;
    
    private final List<WebSocketConnection> connections =
            new LinkedList<WebSocketConnection>();

    @Override
    protected WebSocketConnection createWebSocketConnection() {
        return new ChatConnection();
    }

    private final class ChatConnection extends WebSocketConnection {
        
        private static final long maxMessageSize = 65536; // 64KB
        
        @Override
        protected void onOpen() {
            connections.add(this);
        }
        
        @Override
        protected void onMessage(WebSocketFrame frame) throws IOException {
            // There may be some clever way to fork input streams in
            // a 1) single-threaded and 2) blocking environment, but I
            // haven't discovered it yet. For now, we'll require that
            // people only send reasonable-sized chat messages.
            if(frame.getPayloadLength() > maxMessageSize) {
                System.err.println("Payload too big for a chat message");
                swallowFrame(frame);
                close();
            }
            
            // Pull the payload into a byte array
            byte[] payload = frame.getPayloadArray();
            
            for(WebSocketConnection connection : connections) {
                frame.setPayload(payload);
                connection.send(frame);
            }
        }
        
        @Override
        protected void onClose() {
            connections.remove(this);
        }
        
        @Override
        protected void onError() {
            System.err.println("Chat WebSocket error");
        }
    }
}
