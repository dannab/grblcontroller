package in.co.gorest.grblcontroller.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class TelnetProtocolDecoderTest {

    @Test
    public void passesFluidNcTextAndRemovesNegotiation() {
        RecordingListener listener = new RecordingListener();
        TelnetProtocolDecoder decoder = new TelnetProtocolDecoder(listener);
        int[] input = {'G', 'r', 'b', 'l', 255, 251, 1, ' ', '3', '.', '9', '\r', '\n'};

        for (int value : input) decoder.accept(value);

        assertEquals("Grbl 3.9\r\n", listener.text());
        assertEquals(1, listener.replies.size());
        assertArrayEquals(new byte[]{(byte) 255, (byte) 254, 1}, listener.replies.get(0));
    }

    @Test
    public void declinesServerRequestAndSkipsSubnegotiation() {
        RecordingListener listener = new RecordingListener();
        TelnetProtocolDecoder decoder = new TelnetProtocolDecoder(listener);
        int[] input = {255, 253, 3, 255, 250, 24, 1, 255, 240, 'o', 'k', '\n'};

        for (int value : input) decoder.accept(value);

        assertEquals("ok\n", listener.text());
        assertArrayEquals(new byte[]{(byte) 255, (byte) 252, 3}, listener.replies.get(0));
    }

    private static final class RecordingListener implements TelnetProtocolDecoder.Listener {
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private final List<byte[]> replies = new ArrayList<>();

        @Override
        public void onDataByte(int value) {
            data.write(value);
        }

        @Override
        public void onReply(byte[] reply) {
            replies.add(reply);
        }

        private String text() {
            return new String(data.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
