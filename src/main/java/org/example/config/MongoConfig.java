package org.example.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
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
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;

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

    @Bean
    public MongoClient mongoClient() {
        String resolvedUri = resolveMongoUri();
        ConnectionString connectionString = new ConnectionString(resolvedUri);
        
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
                    logger.warn("MongoDB server {} encountered error: {}", 
                            current.getAddress(), current.getException().getMessage());
                }
            }
        };

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToSslSettings(builder -> builder.enabled(false))
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
                .retryReads(true)
                .build();
        
        logger.info("Creating MongoDB client with connection timeout: {}ms, socket timeout: {}ms, server selection timeout: {}ms",
                connectTimeout, socketTimeout, serverSelectionTimeout);
        
        return MongoClients.create(settings);
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

    private String resolveMongoUri() {
        if (mongoUri != null && !mongoUri.isBlank()) {
            return mongoUri.trim();
        }

        if (mongoDbUsername == null || mongoDbUsername.isBlank()
                || mongoDbHostname == null || mongoDbHostname.isBlank()
                || mongoDbName == null || mongoDbName.isBlank()) {
            throw new IllegalStateException("Mongo configuration is missing. Set mongo_uri or mongo_db_* properties.");
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

