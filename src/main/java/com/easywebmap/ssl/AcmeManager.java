package com.easywebmap.ssl;

import com.easywebmap.EasyWebMap;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class AcmeManager {
    private static final String PRODUCTION_URL = "acme://letsencrypt.org";
    private static final String STAGING_URL = "acme://letsencrypt.org/staging";
    private static final int KEY_SIZE = 2048;
    private static final Duration RENEWAL_THRESHOLD = Duration.ofDays(30);

    private final EasyWebMap plugin;
    private final Path sslDir;
    private final Path accountKeyFile;
    private final Path domainKeyFile;
    private final Path domainCertFile;

    private final ConcurrentHashMap<String, String> pendingChallenges = new ConcurrentHashMap<>();
    private final AtomicReference<SslContext> currentSslContext = new AtomicReference<>();
    private final AtomicReference<Instant> certExpiry = new AtomicReference<>();

    private ScheduledExecutorService renewalScheduler;
    private volatile boolean initialized = false;

    public AcmeManager(EasyWebMap plugin) {
        this.plugin = plugin;
        this.sslDir = plugin.getDataDirectory().resolve("ssl");
        this.accountKeyFile = sslDir.resolve("account.key");
        this.domainKeyFile = sslDir.resolve("domain.key");
        this.domainCertFile = sslDir.resolve("domain.crt");
    }

    public CompletableFuture<Boolean> initialize() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(sslDir);

                if (Files.exists(domainCertFile) && Files.exists(domainKeyFile)) {
                    if (loadExistingCertificate()) {
                        if (!shouldRenew()) {
                            System.out.println("[EasyWebMap] Loaded existing SSL certificate");
                            initialized = true;
                            return true;
                        }
                        System.out.println("[EasyWebMap] Certificate needs renewal");
                    }
                }

                return obtainCertificate();
            } catch (Exception e) {
                System.err.println("[EasyWebMap] SSL initialization failed: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        });
    }

    private boolean loadExistingCertificate() {
        try {
            SslContext ctx = SslContextBuilder.forServer(
                    domainCertFile.toFile(),
                    domainKeyFile.toFile()
            ).build();
            currentSslContext.set(ctx);

            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            try (InputStream is = Files.newInputStream(domainCertFile)) {
                X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
                certExpiry.set(cert.getNotAfter().toInstant());
            }
            return true;
        } catch (Exception e) {
            System.err.println("[EasyWebMap] Failed to load existing certificate: " + e.getMessage());
            return false;
        }
    }

    private boolean obtainCertificate() {
        String domain = plugin.getConfig().getDomain();
        if (domain == null || domain.isBlank()) {
            System.err.println("[EasyWebMap] HTTPS enabled but no domain configured");
            return false;
        }

        System.out.println("[EasyWebMap] Requesting SSL certificate for " + domain);

        try {
            KeyPair accountKeyPair = loadOrCreateKeyPair(accountKeyFile);
            KeyPair domainKeyPair = loadOrCreateKeyPair(domainKeyFile);

            String acmeUrl = plugin.getConfig().isProductionAcme() ? PRODUCTION_URL : STAGING_URL;

            // Set context classloader so ServiceLoader can find ACME providers in plugin JAR
            ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(AcmeManager.class.getClassLoader());
            Session session;
            try {
                session = new Session(acmeUrl);
            } finally {
                Thread.currentThread().setContextClassLoader(originalClassLoader);
            }

            Account account = findOrRegisterAccount(session, accountKeyPair);
            System.out.println("[EasyWebMap] ACME account ready");

            Order order = account.newOrder().domains(domain).create();
            System.out.println("[EasyWebMap] Certificate order created");

            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() == Status.VALID) {
                    continue;
                }
                processAuthorization(auth);
            }

            order.execute(domainKeyPair);

            int attempts = 0;
            while (order.getStatus() != Status.VALID && attempts++ < 30) {
                if (order.getStatus() == Status.INVALID) {
                    throw new AcmeException("Order failed: " + order.getError().orElse(null));
                }
                Thread.sleep(3000);
                order.update();
            }

            if (order.getStatus() != Status.VALID) {
                throw new AcmeException("Order did not complete in time");
            }

            Certificate certificate = order.getCertificate();
            try (FileWriter fw = new FileWriter(domainCertFile.toFile())) {
                certificate.writeCertificate(fw);
            }

            System.out.println("[EasyWebMap] Certificate obtained successfully!");

            pendingChallenges.clear();

            return loadExistingCertificate();

        } catch (Exception e) {
            System.err.println("[EasyWebMap] Failed to obtain certificate: " + e.getMessage());
            e.printStackTrace();
            pendingChallenges.clear();
            return false;
        }
    }

    private KeyPair loadOrCreateKeyPair(Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            try (FileReader fr = new FileReader(keyFile.toFile())) {
                return KeyPairUtils.readKeyPair(fr);
            }
        } else {
            KeyPair keyPair = KeyPairUtils.createKeyPair(KEY_SIZE);
            try (FileWriter fw = new FileWriter(keyFile.toFile())) {
                KeyPairUtils.writeKeyPair(keyPair, fw);
            }
            return keyPair;
        }
    }

    private Account findOrRegisterAccount(Session session, KeyPair accountKeyPair) throws AcmeException {
        AccountBuilder accountBuilder = new AccountBuilder()
                .agreeToTermsOfService()
                .useKeyPair(accountKeyPair);

        String email = plugin.getConfig().getAcmeEmail();
        if (email != null && !email.isBlank()) {
            accountBuilder.addEmail(email);
        }

        return accountBuilder.create(session);
    }

    private void processAuthorization(Authorization auth) throws AcmeException, InterruptedException {
        Http01Challenge challenge = auth.findChallenge(Http01Challenge.class)
                .orElseThrow(() -> new AcmeException("No HTTP-01 challenge available"));

        String token = challenge.getToken();
        String content = challenge.getAuthorization();

        pendingChallenges.put(token, content);
        System.out.println("[EasyWebMap] HTTP-01 challenge ready for token: " + token);

        challenge.trigger();

        int attempts = 0;
        while (auth.getStatus() != Status.VALID && attempts++ < 30) {
            if (auth.getStatus() == Status.INVALID) {
                pendingChallenges.remove(token);
                throw new AcmeException("Challenge failed: " + challenge.getError().orElse(null));
            }
            Thread.sleep(3000);
            auth.update();
        }

        pendingChallenges.remove(token);

        if (auth.getStatus() != Status.VALID) {
            throw new AcmeException("Authorization did not complete in time");
        }

        System.out.println("[EasyWebMap] Domain validated successfully");
    }

    public String getChallengeResponse(String token) {
        return pendingChallenges.get(token);
    }

    public boolean hasPendingChallenge() {
        return !pendingChallenges.isEmpty();
    }

    public SslContext getSslContext() {
        return currentSslContext.get();
    }

    public boolean isInitialized() {
        return initialized && currentSslContext.get() != null;
    }

    public Instant getCertificateExpiry() {
        return certExpiry.get();
    }

    public boolean shouldRenew() {
        Instant expiry = certExpiry.get();
        if (expiry == null) {
            return true;
        }
        return Instant.now().plus(RENEWAL_THRESHOLD).isAfter(expiry);
    }

    public void startRenewalScheduler() {
        if (renewalScheduler != null) {
            return;
        }

        renewalScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EasyWebMap-SSL-Renewal");
            t.setDaemon(true);
            return t;
        });

        renewalScheduler.scheduleAtFixedRate(() -> {
            try {
                if (shouldRenew()) {
                    System.out.println("[EasyWebMap] Certificate renewal triggered");
                    if (obtainCertificate()) {
                        System.out.println("[EasyWebMap] Certificate renewed successfully");
                        plugin.getWebServer().reloadSslContext(currentSslContext.get());
                    }
                }
            } catch (Exception e) {
                System.err.println("[EasyWebMap] Certificate renewal failed: " + e.getMessage());
            }
        }, 24, 24, TimeUnit.HOURS);
    }

    public CompletableFuture<Boolean> renewNow() {
        return CompletableFuture.supplyAsync(() -> {
            if (obtainCertificate()) {
                plugin.getWebServer().reloadSslContext(currentSslContext.get());
                return true;
            }
            return false;
        });
    }

    public void shutdown() {
        if (renewalScheduler != null) {
            renewalScheduler.shutdownNow();
            renewalScheduler = null;
        }
    }
}
