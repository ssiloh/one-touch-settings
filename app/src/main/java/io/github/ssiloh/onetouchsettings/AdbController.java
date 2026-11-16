package io.github.ssiloh.onetouchsettings;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

/**
 * The phone's own ADB client. It pairs with and connects to the on-device {@code adbd} that Wireless debugging
 * exposes on this very device (loopback), so no computer is involved.
 */
public final class AdbController extends AbsAdbConnectionManager {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    public interface Listener {
        void onStateChanged(@NonNull State state, @NonNull String message);

        default void onPairingPortChanged(int port) {}
    }

    /** Wireless debugging always listens on every interface, so loopback works and needs no LAN permission. */
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private static volatile AdbController instance;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor();

    public static AdbController get(Context context) {
        if (instance == null) {
            synchronized (AdbController.class) {
                if (instance == null) {
                    try {
                        instance = new AdbController(context.getApplicationContext());
                    } catch (Exception e) {
                        throw new IllegalStateException("ADB key setup failed", e);
                    }
                }
            }
        }
        return instance;
    }

    private final Context app;
    private final PrivateKey privateKey;
    private final Certificate certificate;
    private final Object opLock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile State state = State.DISCONNECTED;
    private volatile String message = "";
    private volatile int pairingPort = -1;

    private AdbController(Context context) throws Exception {
        app = context;
        setApi(Build.VERSION.SDK_INT);
        setHostAddress(LOCAL_HOST);
        setTimeout(15, TimeUnit.SECONDS);

        File keyFile = new File(context.getFilesDir(), "adb_private_key.pk8");
        File certFile = new File(context.getFilesDir(), "adb_cert.der");
        PrivateKey key = null;
        Certificate cert = null;
        if (keyFile.exists() && certFile.exists()) {
            try {
                key = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
                cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
            } catch (Exception e) {
                key = null; // corrupt files: regenerate (requires pairing again)
                cert = null;
            }
        }
        if (key == null || cert == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            key = pair.getPrivate();
            cert = createCertificate(pair.getPublic(), key);
            write(keyFile, key.getEncoded());
            write(certFile, cert.getEncoded());
        }
        privateKey = key;
        certificate = cert;
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        file.setReadable(true, true);
    }

    private static Certificate createCertificate(PublicKey publicKey, PrivateKey privateKey) throws Exception {
        String algorithm = "SHA512withRSA";
        X500Name name = new X500Name("CN=One Touch Settings");
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000);
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier",
                new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);
        X509CertImpl certificate = new X509CertImpl(info);
        certificate.sign(privateKey, algorithm);
        return certificate;
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "OneTouchSettings";
    }

    // ---------------------------------------------------------------- state

    public State getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public int getPairingPort() {
        return pairingPort;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void setState(State newState, String newMessage) {
        state = newState;
        message = newMessage;
        main.post(() -> {
            for (Listener l : listeners) l.onStateChanged(newState, newMessage);
        });
    }

    public void setPairingPort(int port) {
        pairingPort = port;
        main.post(() -> {
            for (Listener l : listeners) l.onPairingPortChanged(port);
        });
    }

    // ----------------------------------------------------------- operations

    /** Finds the port of an on-device Wireless debugging service through mDNS. Returns -1 on timeout. */
    public int discoverPort(String serviceType, long timeoutMs) throws InterruptedException {
        AtomicInteger port = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);
        AdbMdns mdns = new AdbMdns(app, serviceType, (host, p) -> {
            if (p > 0) {
                port.set(p);
                latch.countDown();
            }
        });
        mdns.start();
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            mdns.stop();
        }
        return port.get();
    }

    /** Pairs with the on-device pairing service (shown by "Pair device with pairing code"). Blocking. */
    public void pairBlocking(int port, String code) throws Exception {
        synchronized (opLock) {
            pair(LOCAL_HOST, port, code.trim());
        }
    }

    /**
     * Connects to this device's own Wireless debugging service. Blocking; call from a worker thread.
     *
     * @throws AdbPairingRequiredException if the daemon does not know our key yet
     */
    public void connectBlocking(long discoverTimeoutMs) throws Exception {
        synchronized (opLock) {
            if (isConnected()) {
                setState(State.CONNECTED, "연결됨");
                return;
            }
            setState(State.CONNECTING, "무선 디버깅 포트를 찾는 중…");
            try {
                int port = discoverPort(AdbMdns.SERVICE_TYPE_TLS_CONNECT, discoverTimeoutMs);
                if (port < 0) {
                    throw new IOException("무선 디버깅 서비스를 찾지 못했어요. 무선 디버깅이 켜져 있고 Wi-Fi에 연결돼 있나요?");
                }
                setState(State.CONNECTING, "포트 " + port + "에 연결하는 중…");
                setHostAddress(LOCAL_HOST);
                if (!connect(port)) {
                    throw new IOException("ADB 연결에 실패했어요.");
                }
                setState(State.CONNECTED, "연결됨 (127.0.0.1:" + port + ")");
            } catch (AdbPairingRequiredException e) {
                setState(State.DISCONNECTED, "페어링이 필요해요 (처음 한 번만)");
                throw e;
            } catch (Exception e) {
                setState(State.DISCONNECTED, "연결 실패: " + describe(e));
                throw e;
            }
        }
    }

    public void disconnectQuietly() {
        synchronized (opLock) {
            try {
                disconnect();
            } catch (Exception ignored) {
            }
            setState(State.DISCONNECTED, "연결 해제됨");
        }
    }

    /**
     * Runs a shell command on this device as the {@code shell} user. Blocking.
     * stdout and stderr are merged, as with the legacy ADB shell service.
     */
    public String runShell(String command, long timeoutMs) throws Exception {
        AdbStream stream;
        try {
            stream = openStream("shell:" + command);
        } catch (Exception e) {
            if (!isConnected()) setState(State.DISCONNECTED, "연결이 끊어졌어요");
            throw e;
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> guard = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean truncated = false;
        try (InputStream in = stream.openInputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_OUTPUT_BYTES) {
                    out.write(buffer, 0, MAX_OUTPUT_BYTES - out.size());
                    truncated = true;
                    break;
                }
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            if (!timedOut.get() && out.size() == 0) throw e;
        } finally {
            guard.cancel(false);
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        String text = out.toString("UTF-8");
        if (timedOut.get()) text += "\n[시간 초과로 중단됨]";
        if (truncated) text += "\n[출력이 너무 길어 잘렸어요]";
        return text;
    }

    public static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
