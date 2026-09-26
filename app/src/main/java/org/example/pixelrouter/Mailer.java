package org.example.pixelrouter;

import android.content.Context;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Small SMTP-over-TLS client for dedicated accounts on port 465. No plaintext fallback. */
final class Mailer {
    static void send(Context context, String id, String sender, String body) throws Exception {
        String host = Config.get(context, "mail_host").trim();
        String from = address(Config.get(context, "mail_from"));
        String to = address(Config.get(context, "mail_to"));
        String password = Config.get(context, "mail_password");
        if (!host.matches("[a-zA-Z0-9.-]{1,253}"))
            throw new IllegalArgumentException("Invalid SMTP hostname");
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket(host, 465)) {
            socket.setSoTimeout(15_000);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.US_ASCII));
            expect(input, 220);
            command(output, "EHLO pixel-router.local"); expect(input, 250);
            String auth = "\0" + from + "\0" + password;
            command(output, "AUTH PLAIN " + Base64.encodeToString(
                    auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            expect(input, 235);
            command(output, "MAIL FROM:<" + from + ">"); expect(input, 250);
            command(output, "RCPT TO:<" + to + ">"); expect(input, 250, 251);
            command(output, "DATA"); expect(input, 354);
            // Fixed ASCII headers and base64 body prevent SMTP header injection.
            String message = "SMS da " + sender + "\n\n" + body;
            String payload = Base64.encodeToString(message.getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            command(output, "From: <" + from + ">");
            command(output, "To: <" + to + ">");
            command(output, "Subject: SMS ricevuto (id " + id + ")");
            command(output, "Message-ID: <" + id + "@pixel-router.local>");
            command(output, "MIME-Version: 1.0");
            command(output, "Content-Type: text/plain; charset=UTF-8");
            command(output, "Content-Transfer-Encoding: base64");
            command(output, "");
            for (int i = 0; i < payload.length(); i += 76)
                command(output, payload.substring(i, Math.min(i + 76, payload.length())));
            command(output, "."); expect(input, 250);
            command(output, "QUIT");
        }
    }

    private static String address(String raw) {
        String value = raw.trim();
        if (!value.matches("[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9.-]+"))
            throw new IllegalArgumentException("Invalid email address");
        return value;
    }

    private static void command(BufferedWriter out, String line) throws Exception {
        out.write(line); out.write("\r\n"); out.flush();
    }

    private static void expect(BufferedReader in, int... allowed) throws Exception {
        String line;
        int code;
        do {
            line = in.readLine();
            if (line == null || line.length() < 4 || !line.substring(0, 3).matches("[0-9]{3}"))
                throw new java.io.IOException("Malformed SMTP response");
            code = Integer.parseInt(line.substring(0, 3));
        } while (line.charAt(3) == '-');
        for (int expected : allowed) if (code == expected) return;
        throw new java.io.IOException(String.format(Locale.ROOT, "SMTP status %d", code));
    }
    private Mailer() {}
}
