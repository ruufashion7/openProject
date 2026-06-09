package org.example.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ClusterDescription;
import com.mongodb.connection.ClusterType;
import com.mongodb.connection.ServerDescription;
import com.mongodb.event.ClusterDescriptionChangedEvent;
import com.mongodb.event.ClusterListener;
import com.mongodb.event.ServerDescriptionChangedEvent;
import com.mongodb.event.ServerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import javax.crypto.Cipher;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.spec.SecretKeySpec;

@Configuration
public class MongoConfig {
    private static final Logger logger = LoggerFactory.getLogger(MongoConfig.class);
    
    @Value("${mongo_uri:}")
    private String mongoUri;

    @Value("${mongo_db_name:}")
    private String mongoDbName;

    @Value("${mongo_db_hostname:}")
    private String mongoDbHostname;

    @Value("${mongo_db_username:}")
    private String mongoDbUsername;

    @Value("${mongo_db_password:}")
    private String mongoDbPassword;

    @Value("${mongo_db_password_encrypted:false}")
    private boolean mongoDbPasswordEncrypted;

    @Value("${spring.data.mongodb.connect-timeout:15000}")
    private int connectTimeout;

    @Value("${spring.data.mongodb.socket-timeout:60000}")
    private int socketTimeout;

    @Value("${spring.data.mongodb.server-selection-timeout:45000}")
    private int serverSelectionTimeout;

    /**
     * SSLContext algorithm for {@code mongodb+srv} (Atlas). Default {@code TLSv1.2} together with
     * {@link #maybeApplyJdkTlsClientProtocolsForSrv} avoids TLS 1.3 handshakes that some networks
     * answer with {@code SSLException: internal_error} (SSL inspection / middleboxes).
     */
    @Value("${mongo.srv.ssl-context-protocol:TLSv1.2}")
    private String mongoSrvSslContextProtocol;

    /**
     * If non-blank and not {@code -}, sets {@code jdk.tls.client.protocols} before the Mongo client
     * is built (only when not already set on the JVM). Default {@code -} avoids changing TLS for
     * the whole JVM. Env: {@code MONGO_SRV_JDK_TLS_CLIENT_PROTOCOLS}.
     */
    @Value("${mongo.srv.jdk-tls-client-protocols:-}")
    private String mongoSrvJdkTlsClientProtocols;

    /**
     * DNS ordering for {@code mongodb+srv} / direct host resolution in the driver (similar to Node
     * {@code autoSelectFamily: false} / prefer IPv4). {@code ipv4-first} tries A records before AAAA.
     * Env: {@code MONGO_SRV_INET_ADDRESS_FAMILY}.
     */
    @Value("${mongo.srv.inet-address-family:ipv4-first}")
    private String mongoSrvInetAddressFamily;

    /**
     * Trust anchors for Atlas TLS when using {@code mongodb+srv}.
     * <ul>
     *   <li>{@code jdk-cacerts} (default): JDK {@code cacerts} only. Ignores {@code javax.net.ssl.trustStore}
     *       so a corporate PKCS12 in {@code JAVA_TOOL_OPTIONS} does not replace public CAs for Mongo.</li>
     *   <li>{@code merge-jvm-trust-store}: merge JDK {@code cacerts} with {@code javax.net.ssl.trustStore}
     *       (e.g. private CA not in the JDK).</li>
     * </ul>
     */
    @Value("${mongo.srv.trust-strategy:jdk-cacerts}")
    private String mongoSrvTrustStrategy;

    /**
     * {@code dedicated} (default): install a JDK {@code cacerts}-backed {@link SSLContext} for Atlas.
     * {@code driver-default}: do not override TLS — use the driver's / JVM default (useful to compare
     * behaviour; if {@code JAVA_TOOL_OPTIONS} sets a PKCS12 truststore, that store is used instead).
     * Env: {@code MONGO_SRV_TLS_CONTEXT}.
     */
    @Value("${mongo.srv.tls-context:dedicated}")
    private String mongoSrvTlsContext;

    @Bean
    public MongoClient mongoClient() {
        String resolvedUri = resolveMongoUri();
        ConnectionString connectionString = new ConnectionString(resolvedUri);
        if (connectionString.isSrvProtocol()) {
            logger.info("MongoDB target: mongodb+srv (e.g. Atlas), database={}",
                    connectionString.getDatabase() != null && !connectionString.getDatabase().isBlank()
                            ? connectionString.getDatabase() : mongoDbName);
        } else {
            logger.info("MongoDB target: direct hosts={}, database={}",
                    connectionString.getHosts(),
                    connectionString.getDatabase() != null && !connectionString.getDatabase().isBlank()
                            ? connectionString.getDatabase() : mongoDbName);
        }

        final AtomicBoolean loggedAtlasTlsHint = new AtomicBoolean(false);

        // Create connection state listeners for monitoring
        ClusterListener clusterListener = new ClusterListener() {
            @Override
            public void clusterDescriptionChanged(ClusterDescriptionChangedEvent event) {
                ClusterDescription previousDescription = event.getPreviousDescription();
                ClusterDescription currentDescription = event.getNewDescription();
                
                if (currentDescription.getType() == ClusterType.UNKNOWN) {
                    logger.warn("MongoDB cluster state is UNKNOWN. Previous type: {}, Exception: {}", 
                            previousDescription.getType(),
                            currentDescription.getSrvResolutionException() != null 
                                ? currentDescription.getSrvResolutionException().getMessage() 
                                : "none");
                } else if (previousDescription.getType() == ClusterType.UNKNOWN && 
                          currentDescription.getType() != ClusterType.UNKNOWN) {
                    logger.info("MongoDB cluster recovered. Current type: {}, Servers: {}", 
                            currentDescription.getType(),
                            currentDescription.getServerDescriptions().size());
                }
            }
        };

        ServerListener serverListener = new ServerListener() {
            @Override
            public void serverDescriptionChanged(ServerDescriptionChangedEvent event) {
                ServerDescription previous = event.getPreviousDescription();
                ServerDescription current = event.getNewDescription();
                
                if (previous.getException() != null && current.getException() == null) {
                    logger.info("MongoDB server {} recovered from error: {}", 
                            current.getAddress(), previous.getException().getMessage());
                } else if (current.getException() != null && previous.getException() == null) {
                    Throwable ex = current.getException();
                    logger.warn("MongoDB server {} encountered error: {}",
                            current.getAddress(), ex.getMessage());
                    maybeLogAtlasTlsHandshakeHint(ex, loggedAtlasTlsHint);
                }
            }
        };

        // Do NOT disable SSL here: mongodb+srv (MongoDB Atlas) requires TLS.
        // Forcing SSL off breaks Atlas and causes TLS handshake failures.
        MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                // Sessions and user reads must see writes immediately (avoid stale secondaries on Atlas).
                .readPreference(ReadPreference.primary())
                .applyToConnectionPoolSettings(builder -> {
                    builder.minSize(10)
                           .maxSize(100)
                           .maxWaitTime(120000, java.util.concurrent.TimeUnit.MILLISECONDS)
                           .maxConnectionIdleTime(300000, java.util.concurrent.TimeUnit.MILLISECONDS)
                           .maxConnectionLifeTime(0, java.util.concurrent.TimeUnit.MILLISECONDS) // 0 means no limit
                           .maintenanceFrequency(60000, java.util.concurrent.TimeUnit.MILLISECONDS);
                })
                .applyToSocketSettings(builder -> {
                    builder.connectTimeout(connectTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                           .readTimeout(socketTimeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                })
                .applyToServerSettings(builder -> {
                    builder.heartbeatFrequency(10000, java.util.concurrent.TimeUnit.MILLISECONDS)
                           .minHeartbeatFrequency(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                           .addServerListener(serverListener);
                })
                .applyToClusterSettings(builder -> {
                    builder.serverSelectionTimeout(serverSelectionTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                           .localThreshold(15, java.util.concurrent.TimeUnit.MILLISECONDS)
                           .addClusterListener(clusterListener);
                })
                .retryWrites(true)
                .retryReads(true);

        if (connectionString.isSrvProtocol()) {
            maybeApplyJdkTlsClientProtocolsForSrv();
            applyInetAddressResolverForSrv(settingsBuilder);
            if (isDriverDefaultTlsContextForSrv()) {
                logger.info("MongoDB+srv: tls-context=driver-default — not installing a custom SSLContext.");
            } else {
                applyBundledCacertsForAtlasTls(settingsBuilder);
            }
        }

        MongoClientSettings settings = settingsBuilder.build();

        logger.info("Creating MongoDB client with connection timeout: {}ms, socket timeout: {}ms, server selection timeout: {}ms",
                connectTimeout, socketTimeout, serverSelectionTimeout);

        return MongoClients.create(settings);
    }

    /**
     * When {@code JAVA_TOOL_OPTIONS} (or similar) sets {@code javax.net.ssl.trustStore} to a PKCS12
     * that does not include public CAs, TLS to MongoDB Atlas can fail. For {@code mongodb+srv} we build an
     * {@link SSLContext} from JDK {@code cacerts} by default ({@code mongo.srv.trust-strategy=jdk-cacerts})
     * so a global PKCS12 truststore does not affect the Mongo handshake. Optional merge for private CAs.
     */
    private void maybeApplyJdkTlsClientProtocolsForSrv() {
        String raw = mongoSrvJdkTlsClientProtocols == null ? "" : mongoSrvJdkTlsClientProtocols.trim();
        if (raw.isEmpty() || "-".equals(raw)) {
            return;
        }
        String existing = System.getProperty("jdk.tls.client.protocols");
        if (existing != null && !existing.isBlank()) {
            logger.info("MongoDB+srv: jdk.tls.client.protocols already set ({}); not overriding.", existing);
            return;
        }
        System.setProperty("jdk.tls.client.protocols", raw);
        logger.info("MongoDB+srv: set jdk.tls.client.protocols={} (mongo.srv.jdk-tls-client-protocols; set to - to skip).", raw);
    }

    private void applyInetAddressResolverForSrv(MongoClientSettings.Builder settingsBuilder) {
        String mode = mongoSrvInetAddressFamily == null ? "auto" : mongoSrvInetAddressFamily.trim();
        if (mode.isEmpty() || "auto".equalsIgnoreCase(mode)) {
            return;
        }
        settingsBuilder.inetAddressResolver(host -> reorderInetAddresses(host, mode));
        logger.info("MongoDB+srv: inet-address-family={} (mongo.srv.inet-address-family).", mode);
    }

    private List<InetAddress> reorderInetAddresses(String host, String mode) throws UnknownHostException {
        InetAddress[] all = InetAddress.getAllByName(host);
        List<InetAddress> v4 = new ArrayList<>();
        List<InetAddress> v6 = new ArrayList<>();
        for (InetAddress a : all) {
            if (a instanceof Inet4Address) {
                v4.add(a);
            } else if (a instanceof Inet6Address) {
                v6.add(a);
            } else {
                v6.add(a);
            }
        }
        if ("ipv4-only".equalsIgnoreCase(mode)) {
            if (v4.isEmpty()) {
                logger.warn("MongoDB+srv: inet-address-family=ipv4-only but no IPv4 addresses for {}; using JVM order.", host);
                return List.of(all);
            }
            return v4;
        }
        if ("ipv6-only".equalsIgnoreCase(mode)) {
            if (v6.isEmpty()) {
                logger.warn("MongoDB+srv: inet-address-family=ipv6-only but no IPv6 addresses for {}; using JVM order.", host);
                return List.of(all);
            }
            return v6;
        }
        if ("ipv6-first".equalsIgnoreCase(mode)) {
            List<InetAddress> out = new ArrayList<>(v6.size() + v4.size());
            out.addAll(v6);
            out.addAll(v4);
            return out.isEmpty() ? List.of(all) : out;
        }
        if ("ipv4-first".equalsIgnoreCase(mode)) {
            List<InetAddress> out = new ArrayList<>(v4.size() + v6.size());
            out.addAll(v4);
            out.addAll(v6);
            return out.isEmpty() ? List.of(all) : out;
        }
        logger.warn("MongoDB+srv: unknown inet-address-family '{}'; using JVM DNS order for {}.", mode, host);
        return List.of(all);
    }

    /**
     * TLS alert 80 ({@code internal_error}) to Atlas often means the edge rejected the TCP session
     * (IP not on Atlas Network Access) or a broken dual-stack path — not only JVM trust material.
     */
    private void maybeLogAtlasTlsHandshakeHint(Throwable ex, AtomicBoolean once) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("internal_error") || m.contains("alert number 80"))) {
                if (once.compareAndSet(false, true)) {
                    logger.warn(
                            "MongoDB TLS: fatal alert internal_error (80) — not a Java trust-bug: Atlas or the network "
                                    + "path is closing TLS. Fix: Atlas → Network Access → allow your current public IP "
                                    + "(or 0.0.0.0/0 for dev), try VPN off, another network, or Compass from this machine. "
                                    + "Local dev without Atlas: unset MONGO_URI so the app uses mongodb://localhost:27017 "
                                    + "(docker compose up mongo). Optional: MONGO_SRV_TLS_CONTEXT=driver-default to test JVM TLS.");
                }
                return;
            }
        }
    }

    private void applyBundledCacertsForAtlasTls(MongoClientSettings.Builder settingsBuilder) {
        try {
            String protocol = (mongoSrvSslContextProtocol == null || mongoSrvSslContextProtocol.isBlank())
                    ? "TLSv1.2"
                    : mongoSrvSslContextProtocol.trim();
            SSLContext sslContext = sslContextForSrvTls(protocol);
            settingsBuilder.applyToSslSettings(ssl -> ssl.context(sslContext));
            logger.info("MongoDB+srv: TLS context protocol={}, trust={} (mongo.srv.ssl-context-protocol, mongo.srv.trust-strategy).",
                    protocol, trustAnchorsDescription());
        } catch (Exception e) {
            logger.warn("MongoDB+srv: could not build TLS context for Atlas ({}). Using JVM SSL defaults.",
                    e.getMessage());
        }
    }

    private String trustAnchorsDescription() {
        Path p = resolveBundledCacertsPath();
        String base = p != null ? "JDK cacerts at " + p : "JDK cacerts";
        if (isMergeJvmTrustStoreStrategy()) {
            String globalTs = System.getProperty("javax.net.ssl.trustStore");
            if (globalTs != null && !globalTs.isBlank() && Files.isRegularFile(Path.of(globalTs))) {
                return base + " merged with javax.net.ssl.trustStore";
            }
        } else {
            String globalTs = System.getProperty("javax.net.ssl.trustStore");
            if (globalTs != null && !globalTs.isBlank() && Files.isRegularFile(Path.of(globalTs))) {
                return base + " (javax.net.ssl.trustStore ignored for Mongo; set mongo.srv.trust-strategy=merge-jvm-trust-store to merge)";
            }
        }
        return base;
    }

    private boolean isMergeJvmTrustStoreStrategy() {
        String s = mongoSrvTrustStrategy == null ? "" : mongoSrvTrustStrategy.trim();
        return "merge-jvm-trust-store".equalsIgnoreCase(s);
    }

    private boolean isDriverDefaultTlsContextForSrv() {
        String s = mongoSrvTlsContext == null ? "" : mongoSrvTlsContext.trim();
        return "driver-default".equalsIgnoreCase(s);
    }

    private static Path resolveBundledCacertsPath() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }
        Path lib = Path.of(javaHome, "lib", "security", "cacerts");
        if (Files.isRegularFile(lib)) {
            return lib;
        }
        Path conf = Path.of(javaHome, "conf", "security", "cacerts");
        if (Files.isRegularFile(conf)) {
            return conf;
        }
        return null;
    }

    private SSLContext sslContextForSrvTls(String sslProtocol) throws Exception {
        KeyStore anchors = trustAnchorsKeystore();
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(anchors);
        SSLContext sslContext = SSLContext.getInstance(sslProtocol);
        sslContext.init(null, tmf.getTrustManagers(), null);
        return sslContext;
    }

    private KeyStore trustAnchorsKeystore() throws Exception {
        if (isMergeJvmTrustStoreStrategy()) {
            return mergedTrustAnchorsKeystore();
        }
        return loadJdkCacerts();
    }

    /**
     * JDK {@code cacerts} plus optional {@code javax.net.ssl.trustStore} entries (e.g. PKCS12) so
     * replacing the JVM default store globally does not drop public CAs needed for Atlas.
     */
    private KeyStore mergedTrustAnchorsKeystore() throws Exception {
        KeyStore merged = KeyStore.getInstance("JKS");
        merged.load(null, null);
        int seq = copyCertificateEntries(loadJdkCacerts(), merged, "jdk-", 0);
        String extraPath = System.getProperty("javax.net.ssl.trustStore");
        if (extraPath != null && !extraPath.isBlank()) {
            Path p = Path.of(extraPath);
            if (Files.isRegularFile(p)) {
                try {
                    seq = copyCertificateEntries(loadOptionalTrustStoreFile(p), merged, "extra-", seq);
                } catch (Exception e) {
                    logger.warn("MongoDB+srv: could not merge javax.net.ssl.trustStore ({}): {}", p, e.getMessage());
                }
            }
        }
        return merged;
    }

    private static KeyStore loadJdkCacerts() throws Exception {
        Path cacertsPath = resolveBundledCacertsPath();
        if (cacertsPath == null) {
            throw new IllegalStateException("JDK cacerts not found under java.home");
        }
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(cacertsPath)) {
            ks.load(in, "changeit".toCharArray());
        }
        return ks;
    }

    private static KeyStore loadOptionalTrustStoreFile(Path p) throws Exception {
        String type = System.getProperty("javax.net.ssl.trustStoreType");
        if (type == null || type.isBlank()) {
            String n = p.toString().toLowerCase();
            type = (n.endsWith(".p12") || n.endsWith(".pfx")) ? "PKCS12" : "JKS";
        }
        KeyStore ks = KeyStore.getInstance(type);
        char[] pwd = trustStorePasswordChars();
        try (InputStream in = Files.newInputStream(p)) {
            ks.load(in, pwd);
        }
        return ks;
    }

    private static char[] trustStorePasswordChars() {
        String p = System.getProperty("javax.net.ssl.trustStorePassword");
        return p != null ? p.toCharArray() : "changeit".toCharArray();
    }

    private static int copyCertificateEntries(KeyStore from, KeyStore to, String prefix, int start) throws Exception {
        Enumeration<String> aliases = from.aliases();
        int seq = start;
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            if (from.isCertificateEntry(a)) {
                Certificate c = from.getCertificate(a);
                if (c != null) {
                    to.setCertificateEntry(prefix + seq++, c);
                }
            } else if (from.isKeyEntry(a)) {
                Certificate[] chain = from.getCertificateChain(a);
                if (chain != null) {
                    for (Certificate c : chain) {
                        if (c instanceof X509Certificate) {
                            to.setCertificateEntry(prefix + seq++, c);
                        }
                    }
                }
            }
        }
        return seq;
    }

    @Bean
    public MongoDatabaseFactory mongoDatabaseFactory(MongoClient mongoClient) {
        String resolvedUri = resolveMongoUri();
        ConnectionString connectionString = new ConnectionString(resolvedUri);
        String database = connectionString.getDatabase();
        if (database == null || database.isBlank()) {
            database = mongoDbName;
        }
        if (database == null || database.isBlank()) {
            throw new IllegalStateException("Mongo database name is missing.");
        }
        return new SimpleMongoClientDatabaseFactory(mongoClient, database);
    }

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(new FlexibleStringToInstantConverter()));
    }

    @Bean
    public MongoMappingContext mongoMappingContext(MongoCustomConversions conversions) {
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        return context;
    }

    @Bean
    public MappingMongoConverter mappingMongoConverter(MongoDatabaseFactory factory,
                                                       MongoMappingContext context,
                                                       MongoCustomConversions conversions) {
        MappingMongoConverter converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), context);
        converter.setCustomConversions(conversions);
        converter.setMapKeyDotReplacement("_");
        converter.afterPropertiesSet();
        return converter;
    }

    /**
     * Resolves the connection string in a predictable order so gitignored
     * {@code src/main/resources/application-local.properties} works from IntelliJ even when
     * {@code spring.config.import} ordering leaves {@code mongo_uri} at the localhost default.
     */
    private String resolveMongoUri() {
        String env = System.getenv("MONGO_URI");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String fromClasspathLocal = readMongoUriFromClasspathApplicationLocal();
        if (fromClasspathLocal != null) {
            return fromClasspathLocal;
        }
        String fromRootFile = readMongoUriFromProjectRootEnvFile();
        if (fromRootFile != null) {
            return fromRootFile;
        }
        if (mongoUri != null && !mongoUri.isBlank()) {
            return mongoUri.trim();
        }

        if (mongoDbUsername == null || mongoDbUsername.isBlank()
                || mongoDbHostname == null || mongoDbHostname.isBlank()
                || mongoDbName == null || mongoDbName.isBlank()) {
            return "mongodb://localhost:27017/openProject";
        }

        String password = mongoDbPassword == null ? "" : mongoDbPassword;
        if (mongoDbPasswordEncrypted && !password.isBlank()) {
            password = decrypt(password);
        }

        return new StringBuilder("mongodb+srv://")
                .append(mongoDbUsername).append(":")
                .append(password)
                .append("@").append(mongoDbHostname)
                .append("/").append(mongoDbName)
                .toString();
    }

    private static String readMongoUriFromClasspathApplicationLocal() {
        try (InputStream in = MongoConfig.class.getResourceAsStream("/application-local.properties")) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            return trimToNull(p.getProperty("mongo_uri"));
        } catch (IOException ex) {
            return null;
        }
    }

    private static String readMongoUriFromProjectRootEnvFile() {
        Path f = Path.of(System.getProperty("user.dir", ".")).resolve(".env.local.properties");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            return trimToNull(p.getProperty("mongo_uri"));
        } catch (IOException ex) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String decrypt(String secret) {
        try {
            byte[] keyBytes = "-5f0cf48fcfae2bb".getBytes();
            SecretKeySpec key = new SecretKeySpec(keyBytes, "Blowfish");

            BigInteger n = new BigInteger(secret, 16);
            byte[] encoding = n.toByteArray();

            Cipher cipher = Cipher.getInstance("Blowfish");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decode = cipher.doFinal(encoding);
            return new String(decode);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt mongo_db_password.", ex);
        }
    }
}

