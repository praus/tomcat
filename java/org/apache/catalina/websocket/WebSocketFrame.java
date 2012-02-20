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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

import org.apache.catalina.util.Conversions;
import org.apache.catalina.util.IOTools;
import org.apache.coyote.http11.upgrade.UpgradeProcessor;

/* 
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 +-+-+-+-+-------+-+-------------+-------------------------------+
 |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 | |1|2|3|       |K|             |                               |
 +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 |     Extended payload length continued, if payload len == 127  |
 + - - - - - - - - - - - - - - - +-------------------------------+
 |                               |Masking-key, if MASK set to 1  |
 +-------------------------------+-------------------------------+
 | Masking-key (continued)       |          Payload Data         |
 +-------------------------------- - - - - - - - - - - - - - - - +
 :                     Payload Data continued ...                :
 + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - +
 |                     Payload Data continued ...                |
 +---------------------------------------------------------------+
 */

/**
 * Object representation of a WebSocket frame. It knows how to decode itself
 * from an InputStream
 */
public class WebSocketFrame {
    /**
     * The character set used to encode text frames
     */
    private static final Charset textCharset = Charset.forName("UTF-8");
    
    /**
     * The character set decoder used for text frames
     */
    private static final CharsetDecoder charsetDecoder =
	textCharset.newDecoder();
    
    /**
     * The character set decoder should report errors
     */
    static {
        charsetDecoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        charsetDecoder.onMalformedInput(CodingErrorAction.REPORT);
    }
    
    /**
     * FIN bit, every non-fragmented bit should have this set. It has nothing to
     * do with closing of connection.
     */
    private boolean fin;

    /**
     * Type of the frame.
     */
    private OpCode opcode;

    /**
     * Whether this frame's data are masked by maskingKey.
     */
    private boolean mask;

    /**
     * If the payload (data) is masked, it needs to be XORed (in a special way)
     * with this value.
     */
    private byte[] maskingKey;

    /**
     * Length of the payload in bytes
     */
    private long payloadLength;

    /**
     * The payload stream
     */
    private InputStream payload;

    /**
     * Type of frame
     */
    public enum OpCode {
        Continuation(0x0), Text(0x1), Binary(0x2),
        ConnectionClose(0x8), Ping(0x9), Pong(0xA);

        private final int opcode;

        OpCode(int opcode) {
            this.opcode = opcode;
        }

        public int getOpCodeNumber() {
            return this.opcode;
        }

        @Override
        public String toString() {
            return this.name();
        }

        public static OpCode getOpCodeByNumber(int number) throws IOException {
            for (OpCode opcode : OpCode.values()) {
                if (opcode.getOpCodeNumber() == number)
                    return opcode;
            }

            throw new IOException("invalid opcode");
        }
    }

    public enum StatusCode {
        NormalClose(1000), ProtocolError(1002), InvalidData(1007),
        MessageTooBig(1009);
        // TODO there are far more status codes defined:
        // http://tools.ietf.org/html/rfc6455#section-7.4

        private final int statusCode;

        StatusCode(int statusCode) {
            this.statusCode = statusCode;
        }

        public int getStatusCodeNumber() {
            return this.statusCode;
        }

        public byte[] encode() {
            // Status codes are 16-bit unsigned integers
            ByteBuffer code = ByteBuffer.allocate(2);
            code.putShort((short) statusCode);

            // TODO include optional UTF-8 explanation in closing frames
            // (must adjust the size of the buffer accordingly)

            return code.array();
        }
        
        public static boolean isValid(Long statusCode) {
            return (// protocol codes
                    (statusCode >= 1000 && statusCode <= 1011 &&
                    statusCode != 1004 &&
                    statusCode != 1005 &&
                    statusCode != 1006) ||
                    // application-specific codes
                    (statusCode >= 3000 && statusCode < 5000));
        }

        @Override
        public String toString() {
            return this.name();
        }
    }
    
    public static WebSocketFrame decode(final UpgradeProcessor<?> processor)
            throws IOException {
        return decode(new InputStream() {
            @Override
            public int read() throws IOException {
                return processor.read();
            }
        });
    }

    public static WebSocketFrame decode(InputStream input)
            throws IOException {
        // Read the first byte
        int i = input.read();

        if (i == -1) {
            throw new IOException("reached end of stream");
        }

        // Build a frame from scratch
        WebSocketFrame frame = new WebSocketFrame();

        // Set the fin bit
        frame.setFin((i & 0x80) > 0);

        // Extract the reserved bits
        boolean rsv1 = (i & 0x40) > 0;
        boolean rsv2 = (i & 0x20) > 0;
        boolean rsv3 = (i & 0x10) > 0;

        // For now, require all reserved bits to be cleared
        if (rsv1 || rsv2 || rsv3) {
            // TODO better error message for reserved bits
            throw new IOException("reserved bits must not be set");
        }

        // Set the opcode
        frame.setOpcode(OpCode.getOpCodeByNumber((i & 0x0F)));

        // Read the second byte
        i = input.read();

        // Set the mask
        frame.setMask((i & 0x80) > 0);

        // Read the payload length
        // (not set until payload is actually read)
        long payloadLength = i & 0x7F;

        // Read the extended payload length field, if present
        if (payloadLength == 126) {
            // Read the 16-bit field
            byte[] extended = readAll(input, 2);
            
            // Set the actual payload length
            payloadLength = Conversions.byteArrayToLong(extended);
            
        } else if (payloadLength == 127) {
            // Read the 63-bit field
            byte[] extended = readAll(input, 8);

            // Set the actual payload length
            payloadLength = Conversions.byteArrayToLong(extended);
            
        }

        // Read the masking key, if present
        if (frame.isMask()) {
            byte[] maskingKey = readAll(input, 4);
            frame.setMaskingKey(maskingKey);
        } else {
            // This is a server, so require client data to be masked
            // TODO better error message for unmasked client data
            throw new IOException("client data must be masked");
        }
        
        // Decode the payload
        frame.setPayload(input, payloadLength);
        
        // Unmask the payload
        frame.maskPayload();

        // Return the fully decoded frame
        return frame;
    }

    /**
     * Decodes status
     * @return 
     *          Status code of this frame, null if this is not a close frame
     *          or status code is not defined.
     * @throws IOException 
     */
    public Long decodeStatusCode() throws IOException {
        if (!opcode.equals(OpCode.ConnectionClose)) {
            return null;
        }
        if (getPayloadLength() >= 2) {
            byte[] b = readAll(getPayload(), 2);
            long statusCode = Conversions.byteArrayToLong(b);
            return statusCode;
        }
        return null;
    }
    
    /**
     * Writes this frame to the given stream
     * @param OutputStream the stream to write to
     */
    public void encode(OutputStream output) throws IOException {
        // Encode the first byte (flags and opcode)
        int flagsAndOpcode = 0;

        // Set the final fragment bit
        flagsAndOpcode = flagsAndOpcode | (fin ? 0x80 : 0x00);

        // Set reserve bits
        // flagsAndOpcode = flagsAndOpcode | rsv1 | rsv2 | rsv3;

        // Set the opcode
        flagsAndOpcode = flagsAndOpcode | opcode.getOpCodeNumber();

        // Write the first byte (flags and opcode)
        output.write(flagsAndOpcode);

        // Encode the second byte (masking bit and payload length)
        int maskAndLength = 0;

        // Set the masking bit
        maskAndLength = maskAndLength | (mask ? 0x80 : 0x00);
        
        // Determine if we need an extended length field
        byte[] extendedLength = null;

        if (payloadLength > 0xffff) { // 63-bit extended length
            // Set the length field
            maskAndLength = maskAndLength | 127;

            // Write the extended field
            extendedLength = getBytes(payloadLength, 8);

        } else if (payloadLength > 125) { // 16-bit extended length
            // Set the length field
            maskAndLength = maskAndLength | 126;

            // Write the extended field
            extendedLength = getBytes(payloadLength, 2);
        }
        else
        {
            // Set the length field
            maskAndLength = maskAndLength | (int) payloadLength;
        }
        
        // Write the mask and length fields
        output.write(maskAndLength);
        
        // Write the extended length field, if any
        if(extendedLength != null) {
            output.write(extendedLength);
        }
        
        if (mask) {
            // Write the masking key
            output.write(maskingKey);
            
            // Mask the payload
            maskPayload();
        }
        
        // Write the payload
        IOTools.flow(payload, output);
    }

    /**
     * Creates a new control frame for closing the connection normally
     * 
     * @return normal closing frame
     */
    public static WebSocketFrame closeFrame() {
        return new WebSocketFrame(true, OpCode.ConnectionClose,
                StatusCode.NormalClose.encode());
    }
    
    /**
     * Creates a new control frame for closing the connection with
     * the given status code
     * 
     * @return closing frame
     */
    public static WebSocketFrame makeCloseFrame(StatusCode statusCode) {
        return new WebSocketFrame(true, OpCode.ConnectionClose,
    		statusCode.encode());
    }

    /**
     * Wrapper around constructor that allows to easily send a text message.
     * 
     * @param message
     *            text of the message
     * @return frame with message encoded as data in the frame
     */
    public static WebSocketFrame message(String message) {
        return new WebSocketFrame(true, OpCode.Text,
                message.getBytes(textCharset));
    }
    
    /**
     * Convenient method that makes it easy to send a pong reply
     * 
     * @param WebSocketFrame
     *              the ping message to which to reply
     *              
     * @return the reply to the given ping
     */
    public static WebSocketFrame makePong(WebSocketFrame frame) {
        // Actually, we just need to flip the mask and set the new opcode
        frame.toggleMask();
        frame.setOpcode(OpCode.Pong);
        
        return frame;
    }

    /**
     * Private constructor for null frames
     */
    private WebSocketFrame() {
    }

    /**
     * Constructor for frames with unmasked payload
     * 
     * @param fin
     *            whether FIN bit should be set
     * @param opcode
     *            type of frame
     * @param payload
     *            the byte array containing the payload
     */
    public WebSocketFrame(boolean fin, OpCode opcode, byte[] payload) {
        this.fin = fin;
        this.mask = false;
        this.opcode = opcode;
        setPayload(payload);
    }
    
    /**
     * Constructor for frames with unmasked payload
     * 
     * @param fin
     *            whether FIN bit should be set
     * @param opcode
     *            type of frame
     * @param payload
     *            the stream containing the payload (must be finite)
     * @param payloadLength
     *            the length of the payload
     */
    public WebSocketFrame(boolean fin, OpCode opcode,
            InputStream payload, long payloadLength) {
        this.fin = fin;
        this.mask = false;
        this.opcode = opcode;
        setPayload(payload, payloadLength);
    }
    
    @Override
    public String toString() {
        return String.format("FIN:%s OPCODE:%s MASK:%s LEN:%s\n", fin ? "1"
                : "0", opcode, mask ? "1" : "0", payloadLength);
    }

    /**
     * @return Whether this frame is the final frame.
     */
    public boolean isFin() {
        return fin;
    }

    public void setFin(boolean fin) {
        this.fin = fin;
    }

    public OpCode getOpcode() {
        return opcode;
    }

    public void setOpcode(OpCode opcode) {
        this.opcode = opcode;
    }

    /**
     * Indicates whether this frame has Connection Close flag set and therefore
     * the endpoint receiving this frame must close connection.
     */
    public boolean isClose() {
        return getOpcode().equals(OpCode.ConnectionClose);
    }

    public boolean isMask() {
        return mask;
    }

    public void setMask(boolean mask) {
        this.mask = mask;
    }
    
    public void toggleMask() {
        setMask(!mask);
    }

    /**
     * @return true iff this frame contains binary or text data
     */
    public boolean isData() {
        return opcode.equals(OpCode.Binary) || opcode.equals(OpCode.Text);
    }

    /**
     * Finds out whether this frame is a control frame.
     * 
     * @return true iff this frame is a control frame
     */
    public boolean isControl() {
        return opcode.equals(OpCode.ConnectionClose)
                || opcode.equals(OpCode.Ping) || opcode.equals(OpCode.Pong);
    }

    public byte[] getMaskingKey() {
        return maskingKey;
    }

    public void setMaskingKey(byte[] maskingKey) {
        this.maskingKey = maskingKey;
    }
    
    private void maskPayload() {
        payload = new MaskingStream(payload, maskingKey);
    }
    
    public long getPayloadLength() {
        return payloadLength;
    }
    
    /**
     * @returns the payload
     */
    public InputStream getPayload() {
        return payload;
    }
    
    /**
     * @return payload reader with UTF-8 decoding
     */
    public Reader getPayloadReader() {
        return new InputStreamReader(payload, charsetDecoder);
    }
    
    public void setPayload(FiniteStream newPayload) {
        payloadLength = newPayload.remaining();
        payload = newPayload;
    }
    
    public void setPayload(InputStream newPayload, long newPayloadLength) {
        setPayload(new FiniteStream(newPayload, newPayloadLength));
    }
    
    public void setPayload(byte[] newPayload) {
        setPayload(new ByteArrayInputStream(newPayload), newPayload.length);
    }
    
    /**
     * Safely reads available bytes from stream into a byte array
     * @param InputStream the stream to read from
     * @param int the number of bytes to read
     * @return a byte array containing input bytes or null on failure
     * @throws IOException
     */
    private static byte[] readAll(InputStream input, int length)
    throws IOException
    {
        // Declare the byte array
        byte[] buffer = new byte[length];
        
        // See how many bytes are returned
        int totalBytesRead = 0;
        
        // Read up bytes until we have them all or there aren't any more
        while(totalBytesRead < length)
        {
            // Count the number of bytes read
            int bytesRead = input.read(buffer, totalBytesRead,
                    length - totalBytesRead);

            // Check for end of input
            if(bytesRead == -1) break;
            
            // Total the number of bytes read
            totalBytesRead += bytesRead;
        }
        
        // Ensure we read all the bytes
        if(totalBytesRead != length)
        {
            throw new IOException("stopped reading bytes prematurely");
        }
        
        // Return the byte array
        return buffer;
    }
    
    /**
     * Extracts the bytes of an unsigned integer as bytes
     * @param long the unsigned integer whose bytes are to be extracted
     * @param int the number of significant bytes (low-order)
     * @return a byte array representing the bytes of the unsigned integer
     */
    private static byte[] getBytes(long unsignedInt, int numBytes)
    {
        // Initialize the byte array
        byte[] array = new byte[numBytes];
        
        // Extract each bytes
        for(int i = 0; i < array.length; ++i)
        {
            array[numBytes - i - 1] = (byte) (unsignedInt >> (i * 8));
        }
        
        // Return the constructed array
        return array;
    }
}
