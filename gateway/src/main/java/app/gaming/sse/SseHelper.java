
package app.gaming.sse;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Simple helper for writing SSE formatted messages.
 */
public class SseHelper {
    public static void send(OutputStream os, String data) throws Exception {
        String msg = "data: " + data + "\n\n";
        os.write(msg.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }
}
