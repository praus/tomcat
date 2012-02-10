package org.apache.catalina.websocket;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;

import org.apache.catalina.util.Conversions;
import org.apache.coyote.http11.upgrade.UpgradeProcessor;
import org.apache.tomcat.util.buf.B2CConverter;


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
     * Maximum length of the header if all fields are used.
     */
    public static final int MAX_HEADER_LENGTH = 14;
    
    /**
     * FIN bit, every non-fragmented bit should have this set. It has
     * nothing to do with closing of connection.
     */
    private boolean fin;

    /**
     * Type of the frame.
     */
    private OpCode opcode;
    
    /**
     * Status code this frame will be carrying, if any.
     */
    private StatusCode statusCode;

    /**
     * Whether this frame's data are masked by maskingKey.
     */
    private boolean mask;

    /**
     * Size of the payload
     */
    private long payloadLength;

    /**
     * If the payload (data) is masked, it needs to be XORed (in a special way)
     * with this value.
     */
    private byte[] maskingKey;
    private ByteBuffer data;

    /**
     * encoded frame ready for wire transmission
     */
    private ByteBuffer encoded;

    /**
     * Type of frame.
     */
    public enum OpCode {
        Continuation(0x0), Text(0x1), Binary(0x2), ConnectionClose(0x8),
        Ping(0x9), Pong(0xA);

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

        public static OpCode getOpCodeByNumber(int opcode) {
            OpCode o = OpCode.Text;
            for (OpCode opc : OpCode.values()) {
                if (opc.getOpCodeNumber() == opcode)
                    o = opc;
            }
            return o;
        }
    }
    
    public enum StatusCode {
        NormalClose(1000), ProtocolErrorClose(1002), MessageTooBig(1009);
        // TODO: there are far more status codes defined:
        // http://tools.ietf.org/html/rfc6455#section-7.4
        
        private final int statusCode;

        StatusCode(int statusCode) {
            this.statusCode = statusCode;
        }

        public int getStatusCodeNumber() {
            return this.statusCode;
        }

        @Override
        public String toString() {
            return this.name();
        }
    }

    public static WebSocketFrame decode(UpgradeProcessor<?> processor) throws IOException {
        // Read the first byte
        int i = processor.read();

        boolean fin = (i & 0x80) > 0;

        boolean rsv1 = (i & 0x40) > 0;
        boolean rsv2 = (i & 0x20) > 0;
        boolean rsv3 = (i & 0x10) > 0;

        if (rsv1 || rsv2 || rsv3) {
            // TODO: Not supported.
        }

        OpCode opCode = OpCode.getOpCodeByNumber((i & 0x0F));
        validateOpCode(opCode);
        
        // Read the next byte
        i = processor.read();
        
        boolean mask = (i & 0x80) == 0;
        if (!mask) {
            // Client data must be masked and this isn't
            // TODO: Better message
            throw new IOException();
        }
        
        long payloadLength = i & 0x7F;
        if (payloadLength == 126) {
            byte[] extended = new byte[2];
            processor.read(extended);
            payloadLength = Conversions.byteArrayToLong(extended);
        } else if (payloadLength == 127) {
            byte[] extended = new byte[8];
            processor.read(extended);
            payloadLength = Conversions.byteArrayToLong(extended);
        }

        byte[] maskingKey = new byte[4];
        processor.read(maskingKey);
        
        WebSocketFrame frame = new WebSocketFrame(fin, opCode, mask,
                payloadLength, maskingKey);
        
        return frame;
    }
    
    private static void validateOpCode(OpCode opCode) throws IOException {
        switch (opCode) {
        case Continuation:
        case Text:
        case Binary:
        case ConnectionClose:
        case Ping:
        case Pong:
            break;
        default:
            // TODO: Message
            throw new IOException();
        }
    }
    
    /**
     * Encodes contents of this frame into bytes stored in ByteBuffer
     * 
     * @return buffer with encoded data, ready for reading (flipped)
     */
    public InputStream encode() {
        encoded = ByteBuffer.allocate(MAX_HEADER_LENGTH + data.limit());
        //byte flags = 0b1000; // fin
        int flags = fin ? 1 : 0; // just fin without extensions
        byte opcode = (byte) this.opcode.getOpCodeNumber();
        encoded.put((byte) (opcode | (flags << 7)));

        byte mask = 0;
        int payloadLen = data.remaining();

        int firstLen = payloadLen; // first length field
        byte[] extendedLen = new byte[0];
        if (payloadLen > 65536) { // use 64 bit field for really large payloads
            // TODO: implement
            firstLen = 127;
            extendedLen = new byte[8];
            throw new UnsupportedOperationException("Not implemented yet");
        } else if (payloadLen > 125) { // 16 bit field
            firstLen = 126;
            extendedLen = new byte[2];
            extendedLen[0] = (byte) ((payloadLen >> 8) & 0xFF);
            extendedLen[1] = (byte) (payloadLen & 0xFF);
        }
        // include length in the basic header 2-byte header
        encoded.put((byte) ((mask << 7) | (firstLen)));
        
        // put extended len, in case we are sending larger message
        encoded.put(extendedLen);
        
        // if this is a control frame, include a status code
        if (statusCode != null) {
            int sC = statusCode.getStatusCodeNumber();
            encoded.putShort((short) (sC & 0xFFFF));
        }
        encoded.put(data);
        encoded.flip(); // prepare buffer for reading
        
        // The slice of the bytebuffer we allocated has exactly the capacity we 
        // need, we can therefore just use its backing array. We can also be
        // sure it has a backing array.
        ByteArrayInputStream out = new ByteArrayInputStream(encoded.slice().array()); 
        return out;
    }

    /**
     * Creates a new control frame for closing the connection
     * @return closing frame.
     */
    public static WebSocketFrame closeFrame() {
        WebSocketFrame f = new WebSocketFrame(true, OpCode.ConnectionClose,
                false, 0, null); 
        f.setStatusCode(StatusCode.NormalClose);
        return f;
    }
    
    /**
     * Creates a new control frame for closing the connection with protocol
     * error. 
     * @return closing frame
     */
    public static WebSocketFrame protocolErrorCloseFrame() {
        WebSocketFrame f = new WebSocketFrame(true, OpCode.ConnectionClose,
                false, 0, null);
        f.setStatusCode(StatusCode.ProtocolErrorClose);
        return f;
    }
    
    /**
     * Wrapper around constructor that allows to easily send a text message.
     * 
     * @param message
     *            text of the message
     * @return frame with message encoded as data in the frame
     */
    public static WebSocketFrame message(String message) {
        ByteBuffer buf = ByteBuffer.allocate(message.length());
        buf.put(message.getBytes());
        buf.flip();
        WebSocketFrame f = new WebSocketFrame(buf);
        return f;
    }

    /**
     * Constructor with values for default frame
     * 
     * @param data
     *            payload of the frame
     */
    public WebSocketFrame(ByteBuffer data) {
        this(true, OpCode.Text, false, data.remaining(), null, data);
    }
    
    /**
     * Constructor without maskingKey for creating new frames to be encoded.
     *
     * @param fin
     *            whether FIN bit should be set
     * @param opcode
     *            type of frame
     * @param mask
     *            whether this frame is masked
     * @param payloadLength
     *            payload size (capacity of the data buffer)
     */
    public WebSocketFrame(boolean fin, OpCode opcode, long payloadLength) {
        this.fin = fin;
        this.opcode = opcode;
        this.payloadLength = payloadLength;
    }
    
    /**
     * Constructor without data, they are to be specified later or left blank
     * 
     * @param fin
     *            whether FIN bit should be set
     * @param opcode
     *            type of frame
     * @param mask
     *            whether this frame is masked
     * @param payloadLength
     *            payload size (capacity of the data buffer)
     * @param maskingKey
     *            for unmasking data (if mask==true)
     */
    public WebSocketFrame(boolean fin, OpCode opcode, boolean mask,
            long payloadLength, byte[] maskingKey) {
        this(fin, opcode, mask, payloadLength, maskingKey, null);
    }
    
    /**
     * Constructor for setting every aspect of the frame
     * 
     * @param fin
     *            whether FIN bit should be set
     * @param opcode
     *            type of frame
     * @param mask
     *            whether this frame is masked
     * @param payloadLength
     *            payload size (capacity of the data buffer)
     * @param maskingKey
     *            for unmasking data (if mask==true)
     */
    public WebSocketFrame(boolean fin, OpCode opcode, boolean mask,
            long payloadLength, byte[] maskingKey, ByteBuffer data) {
        this.fin = fin;
        this.opcode = opcode;
        this.mask = mask;
        this.payloadLength = payloadLength;
        this.maskingKey = maskingKey;
        this.data = data;
    }

    @Override
    public String toString() {
        return String.format("FIN:%s OPCODE:%s MASK:%s LEN:%s\n", fin ? "1"
                : "0", opcode, mask ? "1" : "0", payloadLength);
    }

    /**
     * Clones the frame object. Note that data buffer is not entirely
     * independent
     * for efficiency purposes. There's no need to really duplicate the frame
     * content since data buffers in frame objects are usually not going to be
     * reused.
     * 
     * @return independent WebSocketFrame instance
     */
    /*@Override
    protected WebSocketFrame clone() {
        return new WebSocketFrame(fin, opcode, mask, payloadLength, maskingKey,
                data.duplicate());
    }*/

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

    public StatusCode getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(StatusCode statusCode) {
        this.statusCode = statusCode;
    }

    /**
     * Indicates whether this frame has Connection Close flag set and
     * therefore the endpoint receiving this frame must close connection.
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

    /**
     * @return true iff this frame contains binary or text data
     */
    public boolean isData() {
        return opcode.equals(OpCode.Binary) || opcode.equals(OpCode.Text);
    }
    
    /**
     * Finds out whether this frame is a control frame. 
     * @return true iff this frame is a control frame
     */
    public boolean isControl() {
        return opcode.equals(OpCode.ConnectionClose) || 
                opcode.equals(OpCode.Ping) || 
                opcode.equals(OpCode.Pong);
    }
    
    public long getPayloadLength() {
        return payloadLength;
    }

    public void setPayloadLength(long payloadLength) {
        this.payloadLength = payloadLength;
    }

    public byte[] getMaskingKey() {
        return maskingKey;
    }

    public void setMaskingKey(byte[] maskingKey) {
        this.maskingKey = maskingKey;
    }

    protected ByteBuffer getDataAsByteBuffer() {
        return data;
    }

    public ByteBuffer getDataCopy() {
        return data.asReadOnlyBuffer();
    }

    public void setData(ByteBuffer data) {
        this.data = data;
    }
}
