package org.dasein.cloud.test.platform;

import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.CloudProvider;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.platform.CDNSupport;
import org.dasein.cloud.platform.Database;
import org.dasein.cloud.platform.DatabaseEngine;
import org.dasein.cloud.platform.DatabaseProduct;
import org.dasein.cloud.platform.Distribution;
import org.dasein.cloud.platform.PlatformServices;
import org.dasein.cloud.platform.RelationalDatabaseSupport;
import org.dasein.cloud.storage.Blob;
import org.dasein.cloud.test.DaseinTestManager;
import org.dasein.cloud.test.storage.StorageResources;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Manages all identity resources for automated provisioning and de-provisioning during integration tests.
 * <p>Created by George Reese: 2/18/13 10:16 AM</p>
 * @author George Reese
 * @version 2013.04 initial version
 * @since 2013.04
 */
public class PlatformResources {
    static private final Logger logger = Logger.getLogger(PlatformResources.class);

    static private final Random random = new Random();

    private final HashMap<String,String> testCDNs  = new HashMap<String, String>();
    private final HashMap<String,String> testRDBMS = new HashMap<String, String>();

    private CloudProvider   provider;

    public PlatformResources(@Nonnull CloudProvider provider) {
        this.provider = provider;
    }

    public void close() {
        try {
            PlatformServices services = provider.getPlatformServices();

            if( services != null ) {
                RelationalDatabaseSupport rdbmsSupport = services.getRelationalDatabaseSupport();

                if( rdbmsSupport != null ) {
                    for( Map.Entry<String,String> entry : testRDBMS.entrySet() ) {
                        if( !entry.getKey().equals(DaseinTestManager.STATELESS) ) {
                            try {
                                Database db = rdbmsSupport.getDatabase(entry.getValue());

                                if( db != null ) {
                                    rdbmsSupport.removeDatabase(entry.getValue());
                                }
                            }
                            catch( Throwable ignore ) {
                                // ignore
                            }
                        }
                    }
                }
                ArrayList<Future<Boolean>> results = new ArrayList<Future<Boolean>>();

                CDNSupport cdnSupport = services.getCDNSupport();

                if( cdnSupport != null ) {
                    for( Map.Entry<String,String> entry : testCDNs.entrySet() ) {
                        if( !entry.getKey().equals(DaseinTestManager.STATELESS) ) {
                            try {
                                Distribution d = cdnSupport.getDistribution(entry.getValue());

                                if( d != null ) {
                                    results.add(cleanCDN(cdnSupport, entry.getValue()));
                                }
                            }
                            catch( Throwable ignore ) {
                                // ignore
                            }
                        }
                    }
                }
                boolean done;

                do {
                    done = true;
                    try { Thread.sleep(15000L); }
                    catch( InterruptedException ignore ) { }
                    for( Future<Boolean> result : results ) {
                        if( !result.isDone() ) {
                            done = false;
                            break;
                        }
                    }
                } while( !done );
            }
        }
        catch( Throwable ignore ) {
            // ignore
        }
        provider.close();
    }

    ExecutorService service = Executors.newCachedThreadPool();

    private Future<Boolean> cleanCDN(final @Nonnull CDNSupport support, final @Nonnull String distributionId) {
        return service.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                support.delete(distributionId);
                return true;
            }
        });
    }

    public void report() {
        boolean header = false;

        testRDBMS.remove(DaseinTestManager.STATELESS);
        if( !testRDBMS.isEmpty() ) {
            logger.info("Provisioned Platform Resources:");
            header = true;
            DaseinTestManager.out(logger, null, "---> RDBMS Instances", testRDBMS.size() + " " + testRDBMS);
        }
        testCDNs.remove(DaseinTestManager.STATELESS);
        if( !testCDNs.isEmpty() ) {
            if( !header ) {
                logger.info("Provisioned Platform Resources:");
            }
            DaseinTestManager.out(logger, null, "---> CDN Distributions", testCDNs.size() + " " + testCDNs);
        }
    }

    public @Nullable String getTestDistributionId(@Nonnull String label, boolean provisionIfNull, @Nullable String origin) {
        if( label.equals(DaseinTestManager.STATELESS) ) {
            for( Map.Entry<String,String> entry : testCDNs.entrySet() ) {
                if( !entry.getKey().startsWith(DaseinTestManager.REMOVED) ) {
                    String id = entry.getValue();

                    if( id != null ) {
                        return id;
                    }
                }
            }
            return findStatelessDistribution();
        }
        String id = testCDNs.get(label);

        if( id != null ) {
            return id;
        }
        if( provisionIfNull ) {
            PlatformServices services = provider.getPlatformServices();

            if( services != null ) {
                CDNSupport support = services.getCDNSupport();

                if( support != null ) {
                    try {
                        return provisionDistribution(support, label, "Dasein CDN", origin);
                    }
                    catch( Throwable ignore ) {
                        // ignore
                    }
                }
            }
        }
        return null;
    }


    public @Nullable String getTestRDBMSId(@Nonnull String label, boolean provisionIfNull, @Nullable DatabaseEngine engine) {
        if( label.equals(DaseinTestManager.STATELESS) ) {
            for( Map.Entry<String,String> entry : testRDBMS.entrySet() ) {
                if( !entry.getKey().equals(DaseinTestManager.REMOVED) ) {
                    String id = entry.getValue();

                    if( id != null ) {
                        return id;
                    }
                }
            }
            return findStatelessRDBMS();
        }
        String id = testRDBMS.get(label);

        if( id != null ) {
            return id;
        }
        if( provisionIfNull ) {
            PlatformServices services = provider.getPlatformServices();

            if( services != null ) {
                RelationalDatabaseSupport rdbmsSupport = services.getRelationalDatabaseSupport();

                if( rdbmsSupport != null ) {
                    try {
                        return provisionRDBMS(rdbmsSupport, label, "dsnrdbms", engine);
                    }
                    catch( Throwable ignore ) {
                        // ignore
                    }
                }
            }
        }
        return null;
    }

    public @Nullable String findStatelessDistribution() {
        PlatformServices services = provider.getPlatformServices();

        if( services != null ) {
            CDNSupport support = services.getCDNSupport();

            try {
                if( support != null && support.isSubscribed() ) {
                    Iterator<Distribution> dists = support.list().iterator();

                    if( dists.hasNext() ) {
                        String id = dists.next().getProviderDistributionId();

                        testCDNs.put(DaseinTestManager.STATELESS, id);
                        return id;
                    }
                }
            }
            catch( Throwable ignore ) {
                // ignore
            }
        }
        return null;
    }

    public @Nullable String findStatelessRDBMS() {
        PlatformServices services = provider.getPlatformServices();

        if( services != null ) {
            RelationalDatabaseSupport rdbmsSupport = services.getRelationalDatabaseSupport();

            try {
                if( rdbmsSupport != null && rdbmsSupport.isSubscribed() ) {
                    Iterator<Database> databases = rdbmsSupport.listDatabases().iterator();

                    if( databases.hasNext() ) {
                        String id = databases.next().getProviderDatabaseId();

                        testRDBMS.put(DaseinTestManager.STATELESS, id);
                        return id;
                    }
                }
            }
            catch( Throwable ignore ) {
                // ignore
            }
        }
        return null;
    }

    public @Nonnull String provisionDistribution(@Nonnull CDNSupport support, @Nonnull String label, @Nonnull String namePrefix, @Nullable String origin) throws CloudException, InternalException {
        if( origin == null ) {
            StorageResources r = DaseinTestManager.getStorageResources();

            if( r != null ) {
                Blob bucket = r.getTestRootBucket(label, true, null);

                if( bucket != null ) {
                    origin = bucket.getBucketName();
                }
            }
        }
        if( origin == null ) {
            origin = "http://localhost";
        }
        String id = support.create(origin, namePrefix + random.nextInt(10000),  true, "dsncdn" + random.nextInt(10000) + ".dasein.org");

        synchronized( testCDNs ) {
            while( testCDNs.containsKey(label) ) {
                label = label + random.nextInt(9);
            }
            testCDNs.put(label, id);
        }
        return id;
    }

    public @Nonnull String provisionRDBMS(@Nonnull RelationalDatabaseSupport support, @Nonnull String label, @Nonnull String namePrefix, @Nullable DatabaseEngine engine) throws CloudException, InternalException {
        String password = "a" + random.nextInt(100000000);
        String id;

        while( password.length() < 20 ) {
            password = password + random.nextInt(10);
        }
        DatabaseProduct product = null;

        if( engine != null ) {
            for( DatabaseProduct p : support.getDatabaseProducts(engine) ) {
                if( product == null || product.getStandardHourlyRate() > p.getStandardHourlyRate() ) {
                    product = p;
                }
            }
        }
        else {
            for( DatabaseEngine e : support.getDatabaseEngines() ) {
                for( DatabaseProduct p : support.getDatabaseProducts(e) ) {
                    if( product == null || product.getStandardHourlyRate() > p.getStandardHourlyRate() ) {
                        product = p;
                    }
                }
            }
        }
        if( product == null ) {
            throw new CloudException("No database product could be identified");
        }
        String version = support.getDefaultVersion(product.getEngine());

        id = support.createFromScratch(namePrefix + (System.currentTimeMillis()%10000), product, version, "dasein", password, 3000);
        if( id == null ) {
            throw new CloudException("No database was generated");
        }
        synchronized( testRDBMS ) {
            while( testRDBMS.containsKey(label) ) {
                label = label + random.nextInt(9);
            }
            testRDBMS.put(label, id);
        }
        return id;
    }
}
