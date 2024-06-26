package timely.balancer;

import static org.apache.hadoop.fs.FileSystem.FS_DEFAULT_NAME_KEY;
import static timely.Constants.NON_CACHED_METRICS;
import static timely.Constants.NON_CACHED_METRICS_LOCK_PATH;
import static timely.Constants.SERVICE_DISCOVERY_PATH;
import static timely.balancer.Balancer.ASSIGNMENTS_LAST_UPDATED_PATH;
import static timely.balancer.Balancer.ASSIGNMENTS_LOCK_PATH;
import static timely.balancer.Balancer.LEADER_LATCH_PATH;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.atomic.DistributedAtomicLong;
import org.apache.curator.framework.recipes.atomic.DistributedAtomicValue;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.framework.recipes.locks.InterProcessReadWriteLock;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.RetryForever;
import org.apache.curator.x.discovery.ServiceCache;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.details.ServiceCacheListener;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.csvreader.CsvReader;
import com.csvreader.CsvWriter;

import timely.ServerDetails;
import timely.balancer.configuration.BalancerProperties;
import timely.balancer.connection.TimelyBalancedHost;
import timely.common.configuration.CacheProperties;

public class MetricResolver {

    private static final Logger log = LoggerFactory.getLogger(MetricResolver.class);
    private ReentrantReadWriteLock balancerLock = new ReentrantReadWriteLock();
    private Random r = new Random();
    private ScheduledExecutorService assignmentExecutor = Executors.newScheduledThreadPool(2);
    private ScheduledExecutorService arrivalRateExecutor = Executors.newScheduledThreadPool(2);
    private int roundRobinCounter = 0;
    private Set<String> nonCachedMetrics = new HashSet<>();
    private DistributedAtomicValue nonCachedMetricsIP;
    private ReentrantReadWriteLock nonCachedMetricsLocalLock = new ReentrantReadWriteLock();
    private InterProcessReadWriteLock nonCachedMetricsIPRWLock;
    private LeaderLatch leaderLatch;
    private AtomicBoolean isLeader = new AtomicBoolean(false);
    private InterProcessReadWriteLock assignmentsIPRWLock;
    private DistributedAtomicLong assignmentsLastUpdatedInHdfs;
    private AtomicLong assignmentsLastUpdatedLocal = new AtomicLong(0);
    protected List<TimelyBalancedHost> serverList = new ArrayList<>();
    protected Map<String,TimelyBalancedHost> metricToHostMap = new TreeMap<>();
    protected Map<String,ArrivalRate> metricMap = new HashMap<>();
    final protected CuratorFramework curatorFramework;
    final protected BalancerProperties balancerProperties;
    final protected CacheProperties cacheProperties;
    final protected HealthChecker healthChecker;
    final protected Path assignmentFile;
    final protected FileSystem fs;

    private enum BalanceType {
        HIGH_LOW, HIGH_AVG, AVG_LOW
    }

    public MetricResolver(CuratorFramework curatorFramework, BalancerProperties balancerProperties, CacheProperties cacheProperties,
                    HealthChecker healthChecker) throws Exception {
        this.curatorFramework = curatorFramework;
        this.balancerProperties = balancerProperties;
        this.cacheProperties = cacheProperties;
        this.healthChecker = healthChecker;
        this.assignmentFile = new Path(balancerProperties.getAssignmentFile());
        this.fs = getFileSystem(this.assignmentFile);
    }

    public void start() {
        assignmentsIPRWLock = new InterProcessReadWriteLock(curatorFramework, ASSIGNMENTS_LOCK_PATH);
        testIPRWLock(curatorFramework, assignmentsIPRWLock, ASSIGNMENTS_LOCK_PATH);
        startLeaderLatch(curatorFramework);
        startServiceListener(curatorFramework);
        assignmentsLastUpdatedInHdfs = new DistributedAtomicLong(curatorFramework, ASSIGNMENTS_LAST_UPDATED_PATH, new RetryForever(1000));
        try {
            assignmentsLastUpdatedInHdfs.forceSet(getLastWrittenToHdfsTimestamp());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        TreeCacheListener assignmentListener = (curframework, event) -> {
            log.debug("Handling assignments event {}. assignmentsLastUpdatedInHdfs:{}", event.getType().toString(),
                            new Date(assignmentsLastUpdatedInHdfs.get().postValue()));
            if (event.getType().equals(TreeCacheEvent.Type.NODE_UPDATED)) {
                readAssignmentsFromHdfs(true);
            }
        };

        try (TreeCache assignmentTreeCache = new TreeCache(curatorFramework, ASSIGNMENTS_LAST_UPDATED_PATH)) {
            assignmentTreeCache.getListenable().addListener(assignmentListener);
            assignmentTreeCache.start();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        nonCachedMetricsIPRWLock = new InterProcessReadWriteLock(curatorFramework, NON_CACHED_METRICS_LOCK_PATH);
        testIPRWLock(curatorFramework, nonCachedMetricsIPRWLock, NON_CACHED_METRICS_LOCK_PATH);
        nonCachedMetricsIP = new DistributedAtomicValue(curatorFramework, NON_CACHED_METRICS, new RetryForever(1000));
        TreeCacheListener nonCachedMetricsListener = (framework, event) -> {
            if (event.getType().equals(TreeCacheEvent.Type.NODE_UPDATED)) {
                log.debug("Handling nonCachedMetricsIP event {}", event.getType().toString());
                readNonCachedMetricsIP();
            }
        };

        try (TreeCache nonCachedMetricsTreeCache = new TreeCache(curatorFramework, NON_CACHED_METRICS)) {
            nonCachedMetricsTreeCache.getListenable().addListener(nonCachedMetricsListener);
            nonCachedMetricsTreeCache.start();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        nonCachedMetricsLocalLock.writeLock().lock();
        try {
            addNonCachedMetrics(cacheProperties.getNonCachedMetrics());
        } finally {
            nonCachedMetricsLocalLock.writeLock().unlock();
        }
        readNonCachedMetricsIP();

        readAssignmentsFromHdfs(false);

        assignmentExecutor.scheduleAtFixedRate(() -> {
            if (isLeader.get()) {
                try {
                    balance();
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
        }, 15, 15, TimeUnit.MINUTES);

        assignmentExecutor.scheduleAtFixedRate(() -> {
            try {
                if (isLeader.get()) {
                    long lastLocalUpdate = assignmentsLastUpdatedLocal.get();
                    long lastHdfsUpdate = assignmentsLastUpdatedInHdfs.get().postValue();
                    if (lastLocalUpdate > lastHdfsUpdate) {
                        log.debug("Leader writing assignments to hdfs lastLocalUpdate ({}) > lastHdfsUpdate ({})", new Date(lastLocalUpdate),
                                        new Date(lastHdfsUpdate));
                        writeAssignmentsToHdfs();
                    } else {
                        log.trace("Leader not writing assignments to hdfs lastLocalUpdate ({}) <= lastHdfsUpdate ({})", new Date(lastLocalUpdate),
                                        new Date(lastHdfsUpdate));
                    }
                } else {
                    readAssignmentsFromHdfs(true);
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }, 10, 60, TimeUnit.SECONDS);
    }

    public void shutdown() {

        this.assignmentExecutor.shutdown();
        try {
            this.assignmentExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {

        } finally {
            if (!this.assignmentExecutor.isTerminated()) {
                this.assignmentExecutor.shutdownNow();
            }
        }

        this.arrivalRateExecutor.shutdown();
        try {
            this.arrivalRateExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {

        } finally {
            if (!this.arrivalRateExecutor.isTerminated()) {
                this.arrivalRateExecutor.shutdownNow();
            }
        }
    }

    private void readNonCachedMetricsIP() {

        Set<String> currentNonCachedMetricsDistributed = new TreeSet<>();
        try {
            boolean acquired = false;
            while (!acquired) {
                acquired = nonCachedMetricsIPRWLock.readLock().acquire(60, TimeUnit.SECONDS);
            }
            byte[] currentNonCachedMetricsDistributedBytes = nonCachedMetricsIP.get().postValue();
            if (currentNonCachedMetricsDistributedBytes != null) {
                try {
                    currentNonCachedMetricsDistributed = SerializationUtils.deserialize(currentNonCachedMetricsDistributedBytes);
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    currentNonCachedMetricsDistributed = new TreeSet<>();
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            try {
                nonCachedMetricsIPRWLock.readLock().release();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }

        nonCachedMetricsLocalLock.writeLock().lock();
        try {
            if (nonCachedMetrics.containsAll(currentNonCachedMetricsDistributed)) {
                log.debug("local nonCachedMetrics already contains {}", currentNonCachedMetricsDistributed);
            } else {
                currentNonCachedMetricsDistributed.removeAll(nonCachedMetrics);
                log.debug("Adding {} to local nonCachedMetrics", currentNonCachedMetricsDistributed);
                nonCachedMetrics.addAll(currentNonCachedMetricsDistributed);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            nonCachedMetricsLocalLock.writeLock().unlock();
        }
    }

    private void startLeaderLatch(CuratorFramework curatorFramework) {
        try {

            this.leaderLatch = new LeaderLatch(curatorFramework, LEADER_LATCH_PATH);
            this.leaderLatch.start();
            this.leaderLatch.addListener(new LeaderLatchListener() {

                @Override
                public void isLeader() {
                    log.info("this balancer is the leader");
                    isLeader.set(true);
                    writeAssignmentsToHdfs();
                }

                @Override
                public void notLeader() {
                    log.info("this balancer is not the leader");
                    isLeader.set(false);
                }
            });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private void startServiceListener(CuratorFramework curatorFramework) {
        try {
            ServiceDiscovery<ServerDetails> discovery = ServiceDiscoveryBuilder.builder(ServerDetails.class).client(curatorFramework)
                            .basePath(SERVICE_DISCOVERY_PATH).build();
            discovery.start();
            Collection<ServiceInstance<ServerDetails>> instances = discovery.queryForInstances("timely-server");

            balancerLock.writeLock().lock();
            try {
                for (ServiceInstance<ServerDetails> si : instances) {
                    ServerDetails pl = si.getPayload();
                    TimelyBalancedHost tbh = TimelyBalancedHost.of(pl.getHost(), pl.getTcpPort(), pl.getHttpPort(), pl.getWsPort(), pl.getUdpPort(),
                                    new ArrivalRate(arrivalRateExecutor));
                    log.info("adding service {} host:{} tcpPort:{} httpPort:{} wsPort:{} udpPort:{}", si.getId(), pl.getHost(), pl.getTcpPort(),
                                    pl.getHttpPort(), pl.getWsPort(), pl.getUdpPort());
                    tbh.setBalancerProperties(balancerProperties);
                    serverList.add(tbh);
                }
                healthChecker.setTimelyHosts(serverList);
            } finally {
                balancerLock.writeLock().unlock();
            }

            final ServiceCache<ServerDetails> serviceCache = discovery.serviceCacheBuilder().name("timely-server").build();
            ServiceCacheListener listener = new ServiceCacheListener() {

                @Override
                public void cacheChanged() {
                    boolean rebalanceNeeded = false;
                    balancerLock.writeLock().lock();
                    try {
                        List<ServiceInstance<ServerDetails>> instances = serviceCache.getInstances();
                        Set<TimelyBalancedHost> availableHosts = new HashSet<>();
                        for (ServiceInstance<ServerDetails> si : instances) {
                            ServerDetails pl = si.getPayload();
                            TimelyBalancedHost tbh = TimelyBalancedHost.of(pl.getHost(), pl.getTcpPort(), pl.getHttpPort(), pl.getWsPort(), pl.getUdpPort(),
                                            new ArrivalRate(arrivalRateExecutor));
                            tbh.setBalancerProperties(balancerProperties);
                            availableHosts.add(tbh);
                        }

                        List<String> reassignMetrics = new ArrayList<>();
                        // remove hosts that are no longer available
                        Iterator<TimelyBalancedHost> itr = serverList.iterator();
                        while (itr.hasNext()) {
                            TimelyBalancedHost h = itr.next();
                            if (availableHosts.contains(h)) {
                                availableHosts.remove(h);
                            } else {
                                itr.remove();
                                log.info("removing service {}:{} host:{} tcpPort:{} httpPort:{} wsPort:{} udpPort:{}", h.getHost(), h.getTcpPort(), h.getHost(),
                                                h.getTcpPort(), h.getHttpPort(), h.getWsPort(), h.getUdpPort());
                                for (Map.Entry<String,TimelyBalancedHost> e : metricToHostMap.entrySet()) {
                                    if (e.getValue().equals(h)) {
                                        reassignMetrics.add(e.getKey());
                                    }
                                }
                                rebalanceNeeded = true;
                            }
                        }

                        // add new hosts that were not previously known
                        for (TimelyBalancedHost h : availableHosts) {
                            log.info("adding service {}:{} host:{} tcpPort:{} httpPort:{} wsPort:{} udpPort:{}", h.getHost(), h.getTcpPort(), h.getHost(),
                                            h.getTcpPort(), h.getHttpPort(), h.getWsPort(), h.getUdpPort());
                            serverList.add(h);
                            rebalanceNeeded = true;
                        }
                        healthChecker.setTimelyHosts(serverList);

                        if (isLeader.get()) {
                            for (String s : reassignMetrics) {
                                TimelyBalancedHost h = getRoundRobinHost(null);
                                if (h == null) {
                                    unassignMetric(s);
                                    log.debug("Assigned server removed and none available. Unassigning {}", s);
                                } else {
                                    assignMetric(s, h);
                                    log.debug("Assigned server removed. Assigning {} to server {}:{}", s, h.getTcpPort());
                                }
                            }
                        }

                    } finally {
                        balancerLock.writeLock().unlock();
                    }
                    if (isLeader.get()) {
                        if (rebalanceNeeded) {
                            balance();
                        }
                        writeAssignmentsToHdfs();
                    }
                }

                @Override
                public void stateChanged(CuratorFramework curatorFramework, ConnectionState connectionState) {
                    log.info("serviceCache state changed.  Connected:{}", connectionState.isConnected());
                }
            };
            serviceCache.addListener(listener);
            serviceCache.start();

        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private TimelyBalancedHost getLeastUsedHost() {

        TimelyBalancedHost tbh = null;
        balancerLock.readLock().lock();
        try {
            Map<Double,TimelyBalancedHost> rateSortedHosts = new TreeMap<>();
            for (TimelyBalancedHost s : serverList) {
                rateSortedHosts.put(s.getArrivalRate(), s);
            }

            Iterator<Map.Entry<Double,TimelyBalancedHost>> itr = rateSortedHosts.entrySet().iterator();

            while (itr.hasNext() && tbh == null) {
                TimelyBalancedHost currentTBH = itr.next().getValue();
                if (currentTBH.isUp()) {
                    tbh = currentTBH;
                }
            }
        } finally {
            balancerLock.readLock().unlock();
        }
        return tbh;
    }

    private TimelyBalancedHost getRandomHost(TimelyBalancedHost notThisOne) {

        TimelyBalancedHost tbh = null;
        balancerLock.readLock().lock();
        try {
            for (int x = 0; tbh == null && x < serverList.size(); x++) {
                tbh = serverList.get(Math.abs(r.nextInt() & Integer.MAX_VALUE) % serverList.size());
                if (!tbh.isUp()) {
                    tbh = null;
                } else if (notThisOne != null && tbh.equals(notThisOne)) {
                    tbh = null;
                }
            }
        } finally {
            balancerLock.readLock().unlock();
        }
        return tbh;
    }

    private TimelyBalancedHost getRoundRobinHost(TimelyBalancedHost notThisOne) {
        TimelyBalancedHost tbh = null;
        balancerLock.readLock().lock();
        try {
            int maxAttempts = serverList.size();
            int currentAttempt = 0;
            while (tbh == null && currentAttempt < maxAttempts) {
                try {
                    currentAttempt++;
                    tbh = serverList.get(roundRobinCounter % serverList.size());
                    if (!tbh.isUp()) {
                        tbh = null;
                    }
                    if (notThisOne != null && notThisOne.equals(tbh)) {
                        tbh = null;
                    }
                } finally {
                    roundRobinCounter++;
                    if (roundRobinCounter == Integer.MAX_VALUE) {
                        roundRobinCounter = 0;
                    }
                }
            }
            if (tbh == null) {
                tbh = getRandomHost(notThisOne);
            }
        } finally {
            balancerLock.readLock().unlock();
        }
        return tbh;
    }

    private TimelyBalancedHost chooseHost(Set<TimelyBalancedHost> potentialHosts, Map<TimelyBalancedHost,Double> calculatedRates, double referenceRate,
                    BalanceType balanceType) {

        TimelyBalancedHost tbh = null;
        Map<Long,TimelyBalancedHost> weightedList = new TreeMap<>();
        long cumulativeWeight = 0;
        for (TimelyBalancedHost h : potentialHosts) {
            double currentDiff;
            if (balanceType.equals(BalanceType.HIGH_LOW) || balanceType.equals(BalanceType.HIGH_AVG)) {
                currentDiff = referenceRate - calculatedRates.get(h);
            } else {
                currentDiff = calculatedRates.get(h) - referenceRate;
            }
            cumulativeWeight += Math.round(currentDiff);
            weightedList.put(cumulativeWeight, h);
        }

        if (cumulativeWeight > 0) {
            long randomWeight = r.nextLong() % cumulativeWeight;
            for (Map.Entry<Long,TimelyBalancedHost> e : weightedList.entrySet()) {
                if (randomWeight <= e.getKey()) {
                    tbh = e.getValue();
                    break;
                }
            }
        }
        return tbh;
    }

    private int balance() {

        int numReassigned = 0;
        if (isLeader.get()) {
            double controlBandPercentage = balancerProperties.getControlBandPercentage();
            // save current rates so that we can modify
            double totalArrivalRate = 0;
            Map<TimelyBalancedHost,Double> calculatedRates = new HashMap<>();
            for (TimelyBalancedHost h : serverList) {
                double tbhArrivalRate = h.getArrivalRate();
                calculatedRates.put(h, tbhArrivalRate);
                totalArrivalRate += tbhArrivalRate;
            }
            double averageArrivalRate = totalArrivalRate / serverList.size();

            Set<TimelyBalancedHost> highHosts = new HashSet<>();
            Set<TimelyBalancedHost> lowHosts = new HashSet<>();
            Set<TimelyBalancedHost> avgHosts = new HashSet<>();

            double controlHighLimit = averageArrivalRate * (1.0 + controlBandPercentage);
            double controlLowLimit = averageArrivalRate * (1.0 - controlBandPercentage);

            for (TimelyBalancedHost h : serverList) {
                if (h.isUp()) {
                    double currRate = calculatedRates.get(h);
                    if (currRate < controlLowLimit) {
                        lowHosts.add(h);
                    } else if (currRate > controlHighLimit) {
                        highHosts.add(h);
                    } else {
                        avgHosts.add(h);
                    }
                }
            }

            if (highHosts.isEmpty() && lowHosts.isEmpty()) {
                log.debug("Host's arrival rates are within {} of the average", controlBandPercentage);
            } else if (!highHosts.isEmpty() && !lowHosts.isEmpty()) {
                log.debug("begin rebalancing {}", BalanceType.HIGH_LOW);
                numReassigned = rebalance(highHosts, lowHosts, calculatedRates, averageArrivalRate, BalanceType.HIGH_LOW);
                log.debug("end rebalancing {} - reassigned {}", BalanceType.HIGH_LOW, numReassigned);
            } else if (lowHosts.isEmpty()) {
                log.debug("begin rebalancing {}", BalanceType.HIGH_AVG);
                numReassigned = rebalance(highHosts, avgHosts, calculatedRates, averageArrivalRate, BalanceType.HIGH_AVG);
                log.debug("end rebalancing {} - reassigned {}", BalanceType.HIGH_AVG, numReassigned);
            } else {
                log.debug("begin rebalancing {}", BalanceType.AVG_LOW);
                numReassigned = rebalance(avgHosts, lowHosts, calculatedRates, averageArrivalRate, BalanceType.AVG_LOW);
                log.debug("end rebalancing {} - reassigned {}", BalanceType.AVG_LOW, numReassigned);
            }
        }
        return numReassigned;
    }

    public int rebalance(Set<TimelyBalancedHost> losingHosts, Set<TimelyBalancedHost> gainingHosts, Map<TimelyBalancedHost,Double> calculatedRates,
                    double targetArrivalRate, BalanceType balanceType) {

        int numReassigned = 0;
        if (isLeader.get()) {
            balancerLock.writeLock().lock();
            try {
                Map<String,ArrivalRate> tempMetricMap = new HashMap<>();
                tempMetricMap.putAll(metricMap);

                Set<TimelyBalancedHost> focusedHosts;
                Set<TimelyBalancedHost> selectFromHosts;
                if (balanceType.equals(BalanceType.HIGH_LOW) || balanceType.equals(BalanceType.HIGH_AVG)) {
                    focusedHosts = losingHosts;
                    selectFromHosts = gainingHosts;
                } else {
                    focusedHosts = gainingHosts;
                    selectFromHosts = losingHosts;
                }

                for (TimelyBalancedHost h : focusedHosts) {
                    double desiredChange;
                    if (balanceType.equals(BalanceType.HIGH_LOW) || balanceType.equals(BalanceType.HIGH_AVG)) {
                        desiredChange = h.getArrivalRate() - targetArrivalRate;
                    } else {
                        desiredChange = targetArrivalRate - h.getArrivalRate();
                    }

                    // sort metrics by rate
                    Map<Double,String> rateSortedMetrics = new TreeMap<>(Collections.reverseOrder());
                    for (Map.Entry<String,ArrivalRate> e : tempMetricMap.entrySet()) {
                        TimelyBalancedHost tbh = metricToHostMap.get(e.getKey());
                        if (tbh != null && tbh.equals(h)) {
                            rateSortedMetrics.put(e.getValue().getRate(), e.getKey());
                        }
                    }

                    log.trace("focusHost {}:{} desiredChange:{} rateSortedMetrics.size():{}", h.getHost(), h.getTcpPort(), desiredChange,
                                    rateSortedMetrics.size());

                    boolean doneWithHost = false;
                    while (!doneWithHost) {
                        Iterator<Map.Entry<Double,String>> itr = rateSortedMetrics.entrySet().iterator();
                        while (!doneWithHost && itr.hasNext()) {
                            Map.Entry<Double,String> e = null;
                            // find largest metric that does not exceed desired change
                            while (e == null && itr.hasNext()) {
                                e = itr.next();
                                if (e.getKey() > desiredChange) {
                                    log.trace("Skipping:{} rate:{}", e.getValue(), e.getKey());
                                    e = null;
                                }
                            }

                            if (e == null) {
                                log.trace("no metric small enough");
                                doneWithHost = true;
                            } else {
                                log.trace("Selected:{} rate:{}", e.getValue(), e.getKey());
                                TimelyBalancedHost candidateHost = chooseHost(selectFromHosts, calculatedRates, calculatedRates.get(h), balanceType);
                                if (candidateHost == null) {
                                    log.trace("candidate host is null");
                                    doneWithHost = true;
                                } else {
                                    String metric = e.getValue();
                                    Double metricRate = e.getKey();
                                    assignMetric(metric, candidateHost);
                                    numReassigned++;
                                    calculatedRates.put(candidateHost, calculatedRates.get(candidateHost) + metricRate);
                                    calculatedRates.put(h, calculatedRates.get(h) - metricRate);
                                    desiredChange -= metricRate;
                                    // don't move this metric again this host or balance
                                    itr.remove();
                                    tempMetricMap.remove(metric);
                                    log.info("rebalancing: reassigning metric:{} rate:{} from server {}:{} to {}:{} remaining delta {}", metric, metricRate,
                                                    h.getHost(), h.getTcpPort(), candidateHost.getHost(), candidateHost.getTcpPort(), desiredChange);
                                }
                            }
                        }
                        if (!itr.hasNext()) {
                            log.trace("Reached end or rateSortedMetrics");
                            doneWithHost = true;
                        }
                        if (balanceType.equals(BalanceType.HIGH_LOW) || balanceType.equals(BalanceType.HIGH_AVG)) {
                            if (calculatedRates.get(h) <= targetArrivalRate) {
                                doneWithHost = true;
                                log.trace("calculatedRates.get(h) <= targetArivalRate");
                            }
                        } else {
                            if (calculatedRates.get(h) >= targetArrivalRate) {
                                doneWithHost = true;
                                log.trace("calculatedRates.get(h) >= targetArivalRate");
                            }
                        }
                    }
                }
            } finally {
                balancerLock.writeLock().unlock();
            }
        }
        return numReassigned;
    }

    private void addNonCachedMetrics(Collection<String> nonCachedMetricsUpdate) {
        if (!nonCachedMetricsUpdate.isEmpty()) {
            try {
                log.debug("Adding {} to local nonCachedMetrics", nonCachedMetricsUpdate);
                nonCachedMetricsLocalLock.writeLock().lock();
                try {
                    nonCachedMetrics.addAll(nonCachedMetricsUpdate);
                } finally {
                    nonCachedMetricsLocalLock.writeLock().unlock();
                }

                try {
                    boolean acquired = false;
                    while (!acquired) {
                        acquired = nonCachedMetricsIPRWLock.writeLock().acquire(60, TimeUnit.SECONDS);
                    }
                    byte[] currentNonCachedMetricsDistributedBytes = nonCachedMetricsIP.get().postValue();
                    Set<String> currentNonCachedMetricsIP;
                    if (currentNonCachedMetricsDistributedBytes == null) {
                        currentNonCachedMetricsIP = new TreeSet<>();
                    } else {
                        currentNonCachedMetricsIP = SerializationUtils.deserialize(currentNonCachedMetricsDistributedBytes);
                    }
                    if (currentNonCachedMetricsIP.containsAll(nonCachedMetricsUpdate)) {
                        log.debug("nonCachedMetricsIP already contains {}", nonCachedMetricsUpdate);
                    } else {
                        nonCachedMetricsUpdate.removeAll(currentNonCachedMetricsIP);
                        log.debug("Adding {} to nonCachedMetricsIP", nonCachedMetricsUpdate);
                        TreeSet<String> updateSet = new TreeSet<>();
                        updateSet.addAll(currentNonCachedMetricsIP);
                        updateSet.addAll(nonCachedMetricsUpdate);
                        byte[] updateValue = SerializationUtils.serialize(updateSet);
                        nonCachedMetricsIP.trySet(updateValue);
                        if (!nonCachedMetricsIP.get().succeeded()) {
                            nonCachedMetricsIP.forceSet(updateValue);
                        }
                    }
                } finally {
                    nonCachedMetricsIPRWLock.writeLock().release();
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    private void testIPRWLock(CuratorFramework curatorFramework, InterProcessReadWriteLock lock, String path) {
        try {
            lock.writeLock().acquire(10, TimeUnit.SECONDS);
        } catch (Exception e1) {
            try {
                curatorFramework.delete().deletingChildrenIfNeeded().forPath(path);
                curatorFramework.create().creatingParentContainersIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
            } catch (Exception e2) {
                log.info(e2.getMessage(), e2);
            }
        } finally {
            try {
                lock.writeLock().release();
            } catch (Exception e3) {
                log.error(e3.getMessage(), e3);
            }
        }
    }

    private boolean shouldCache(String metricName) {

        if (StringUtils.isBlank(metricName)) {
            return false;
        } else {
            balancerLock.readLock().lock();
            try {
                nonCachedMetricsLocalLock.readLock().lock();
                try {
                    if (nonCachedMetrics.contains(metricName)) {
                        return false;
                    }
                } finally {
                    nonCachedMetricsLocalLock.readLock().unlock();
                }
                if (metricToHostMap.containsKey(metricName)) {
                    return true;
                }
            } finally {
                balancerLock.readLock().unlock();
            }

            nonCachedMetricsLocalLock.writeLock().lock();
            try {
                for (String r : nonCachedMetrics) {
                    if (metricName.matches(r)) {
                        log.debug("Adding {} to list of non-cached metrics", metricName);
                        addNonCachedMetrics(Collections.singleton(metricName));
                        return false;
                    }
                }
            } finally {
                nonCachedMetricsLocalLock.writeLock().unlock();
            }
            return true;
        }
    }

    public TimelyBalancedHost getHostPortKeyIngest(String metric) {

        TimelyBalancedHost tbh = null;
        balancerLock.readLock().lock();
        boolean chooseMetricSpecificHost;
        try {
            chooseMetricSpecificHost = shouldCache(metric) ? true : false;
            ArrivalRate rate = metricMap.get(metric);
            if ((chooseMetricSpecificHost && rate == null) || (!chooseMetricSpecificHost && rate != null)) {
                if (!balancerLock.isWriteLockedByCurrentThread()) {
                    balancerLock.readLock().unlock();
                    balancerLock.writeLock().lock();
                }
            }

            if (chooseMetricSpecificHost) {
                if (rate == null) {
                    rate = new ArrivalRate(arrivalRateExecutor);
                    // already write locked above
                    metricMap.put(metric, rate);
                }
                rate.arrived();
            } else {
                if (rate != null) {
                    // already write locked above
                    metricMap.remove(metric);
                }
                metric = null;
            }

            if (StringUtils.isBlank(metric)) {
                tbh = getRandomHost(null);
            } else {
                tbh = metricToHostMap.get(metric);
                if (tbh == null) {
                    tbh = getRoundRobinHost(null);
                } else if (!tbh.isUp()) {
                    TimelyBalancedHost oldTbh = tbh;
                    tbh = getLeastUsedHost();
                    if (tbh != null) {
                        log.debug("rebalancing from host that is down: reassigning metric {} from server {}:{} to {}:{}", metric, oldTbh.getHost(),
                                        oldTbh.getTcpPort(), tbh.getHost(), tbh.getTcpPort());
                    }
                }
                if (!balancerLock.isWriteLockedByCurrentThread()) {
                    balancerLock.readLock().unlock();
                    balancerLock.writeLock().lock();
                }
                if (tbh != null) {
                    assignMetric(metric, tbh);
                }
            }

            // if all else fails
            if (tbh == null || !tbh.isUp()) {
                for (TimelyBalancedHost h : serverList) {
                    if (h.isUp()) {
                        tbh = h;
                        break;
                    }
                }
                if (tbh != null && StringUtils.isNotBlank(metric)) {
                    if (!balancerLock.isWriteLockedByCurrentThread()) {
                        balancerLock.readLock().unlock();
                        balancerLock.writeLock().lock();
                    }
                    assignMetric(metric, tbh);
                }
            }
        } finally {
            if (balancerLock.isWriteLockedByCurrentThread()) {
                balancerLock.writeLock().unlock();
            } else {
                balancerLock.readLock().unlock();
            }
        }
        if (tbh != null && chooseMetricSpecificHost) {
            tbh.arrived();
        }
        return tbh;
    }

    public TimelyBalancedHost getHostPortKey(String metric) {
        TimelyBalancedHost tbh = null;

        balancerLock.readLock().lock();
        try {
            boolean chooseMetricSpecificHost = shouldCache(metric) ? true : false;
            if (chooseMetricSpecificHost) {
                tbh = metricToHostMap.get(metric);
            } else {
                metric = null;
            }

            if (tbh == null || !tbh.isUp()) {
                for (int x = 0; tbh == null && x < serverList.size(); x++) {
                    tbh = serverList.get(Math.abs(r.nextInt() & Integer.MAX_VALUE) % serverList.size());
                    if (!tbh.isUp()) {
                        tbh = null;
                    }
                }
                if (tbh != null && StringUtils.isNotBlank(metric)) {
                    if (!balancerLock.isWriteLockedByCurrentThread()) {
                        balancerLock.readLock().unlock();
                        balancerLock.writeLock().lock();
                    }
                    assignMetric(metric, tbh);
                }
            }

            // if all else fails
            if (tbh == null || !tbh.isUp()) {
                for (TimelyBalancedHost h : serverList) {
                    if (h.isUp()) {
                        tbh = h;
                        break;
                    }
                }
                if (tbh != null && StringUtils.isNotBlank(metric)) {
                    if (!balancerLock.isWriteLockedByCurrentThread()) {
                        balancerLock.readLock().unlock();
                        balancerLock.writeLock().lock();
                    }
                    assignMetric(metric, tbh);
                }
            }
        } finally {
            if (balancerLock.isWriteLockedByCurrentThread()) {
                balancerLock.writeLock().unlock();
            } else {
                balancerLock.readLock().unlock();
            }
        }
        return tbh;
    }

    private TimelyBalancedHost findHost(String host, int tcpPort) {
        TimelyBalancedHost tbh = null;
        for (TimelyBalancedHost h : serverList) {
            if (h.getHost().equals(host) && h.getTcpPort() == tcpPort) {
                tbh = h;
                break;
            }
        }
        return tbh;
    }

    protected FileSystem getFileSystem(Path path) throws IOException {
        Configuration configuration = new Configuration();
        if (StringUtils.isNotBlank(balancerProperties.getDefaultFs())) {
            configuration.set(FS_DEFAULT_NAME_KEY, balancerProperties.getDefaultFs());
        }
        if (balancerProperties.getFsConfigResources() != null) {
            for (String resource : balancerProperties.getFsConfigResources()) {
                configuration.addResource(new Path(resource));
            }
        }
        return FileSystem.get(path.toUri(), configuration);
    }

    protected void createAssignmentFile() {
        try {
            log.info("Creating assignment file: " + this.assignmentFile);
            this.fs.create(this.assignmentFile);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    protected long getLastWrittenToHdfsTimestamp() {
        try {
            FileStatus fileStatus = this.fs.getFileStatus(this.assignmentFile);
            return fileStatus.getModificationTime();
        } catch (FileNotFoundException e) {
            createAssignmentFile();
            return System.currentTimeMillis();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return System.currentTimeMillis();
        }
    }

    private void readAssignmentsFromHdfs(boolean checkIfNecessary) {

        try {
            long lastLocalUpdate = assignmentsLastUpdatedLocal.get();
            long lastHdfsUpdate = assignmentsLastUpdatedInHdfs.get().postValue();
            if (checkIfNecessary) {
                if (lastHdfsUpdate <= lastLocalUpdate) {
                    log.debug("Not reading assignments from hdfs lastHdfsUpdate ({}) <= lastLocalUpdate ({})", new Date(lastHdfsUpdate),
                                    new Date(lastLocalUpdate));
                    return;
                }
            }
            log.debug("Reading assignments from hdfs lastHdfsUpdate ({}) > lastLocalUpdate ({})", new Date(lastHdfsUpdate), new Date(lastLocalUpdate));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        // proceed with reading from HDFS

        Map<String,TimelyBalancedHost> assignedMetricToHostMap = new TreeMap<>();
        CsvReader reader = null;
        try {
            boolean acquired = false;
            while (!acquired) {
                acquired = assignmentsIPRWLock.readLock().acquire(60, TimeUnit.SECONDS);
            }
            balancerLock.writeLock().lock();
            FSDataInputStream iStream = this.fs.open(this.assignmentFile);
            reader = new CsvReader(iStream, ',', Charset.forName("UTF-8"));
            reader.setUseTextQualifier(false);

            // skip the headers
            boolean success = true;
            success = reader.readHeaders();

            while (success) {
                success = reader.readRecord();
                String[] nextLine = reader.getValues();
                if (nextLine.length >= 3) {
                    String metric = nextLine[0];
                    String host = nextLine[1];
                    int tcpPort = Integer.parseInt(nextLine[2]);
                    TimelyBalancedHost tbh = findHost(host, tcpPort);
                    if (tbh == null) {
                        tbh = getRoundRobinHost(null);
                    } else {
                        log.trace("Found assigment: {} to {}:{}", metric, host, tcpPort);
                    }
                    if (tbh != null) {
                        assignedMetricToHostMap.put(metric, tbh);
                    }
                }
            }

            metricToHostMap.clear();
            for (Map.Entry<String,TimelyBalancedHost> e : assignedMetricToHostMap.entrySet()) {
                if (StringUtils.isNotBlank(e.getKey()) && e.getValue() != null) {
                    if (shouldCache(e.getKey())) {
                        metricToHostMap.put(e.getKey(), e.getValue());
                    }
                } else {
                    Exception e1 = new IllegalStateException("Bad assignment metric:" + e.getKey() + " host:" + e.getValue());
                    log.warn(e1.getMessage(), e1);
                }
            }

            nonCachedMetricsLocalLock.readLock().lock();
            try {
                Iterator<Map.Entry<String,TimelyBalancedHost>> itr = metricToHostMap.entrySet().iterator();
                while (itr.hasNext()) {
                    Map.Entry<String,TimelyBalancedHost> e = itr.next();
                    if (nonCachedMetrics.contains(e.getKey())) {
                        itr.remove();
                    }
                }
            } finally {
                nonCachedMetricsLocalLock.readLock().unlock();
            }
            // remove metric from metricMap (ArrivalRate) if not being cached
            metricMap.entrySet().removeIf(e -> !metricToHostMap.containsKey(e.getKey()));

            assignmentsLastUpdatedLocal.set(assignmentsLastUpdatedInHdfs.get().postValue());
            log.info("Read {} assignments from hdfs lastHdfsUpdate = lastLocalUpdate ({})", metricToHostMap.size(),
                            new Date(assignmentsLastUpdatedLocal.get()));
        } catch (FileNotFoundException e) {
            createAssignmentFile();
            metricToHostMap.clear();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            if (reader != null) {
                reader.close();
            }
            balancerLock.writeLock().unlock();
            try {
                assignmentsIPRWLock.readLock().release();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    private void writeAssignmentsToHdfs() {

        CsvWriter writer = null;
        try {
            boolean acquired = false;
            while (!acquired) {
                acquired = assignmentsIPRWLock.writeLock().acquire(60, TimeUnit.SECONDS);
            }
            balancerLock.readLock().lock();
            nonCachedMetricsLocalLock.readLock().lock();
            try {
                if (!metricToHostMap.isEmpty()) {
                    if (!this.fs.exists(this.assignmentFile.getParent())) {
                        this.fs.mkdirs(this.assignmentFile.getParent());
                    }
                    FSDataOutputStream oStream = this.fs.create(this.assignmentFile, true);
                    writer = new CsvWriter(oStream, ',', Charset.forName("UTF-8"));
                    writer.setUseTextQualifier(false);
                    writer.write("metric");
                    writer.write("host");
                    writer.write("tcpPort");
                    writer.endRecord();
                    for (Map.Entry<String,TimelyBalancedHost> e : metricToHostMap.entrySet()) {
                        if (e.getValue() != null && !nonCachedMetrics.contains(e.getKey())) {
                            writer.write(e.getKey());
                            writer.write(e.getValue().getHost());
                            writer.write(Integer.toString(e.getValue().getTcpPort()));
                            writer.endRecord();
                            log.trace("Saving assigment: {} to {}:{}", e.getKey(), e.getValue().getHost(), e.getValue().getTcpPort());
                        }
                    }

                    long now = System.currentTimeMillis();
                    assignmentsLastUpdatedLocal.set(now);
                    assignmentsLastUpdatedInHdfs.trySet(now);
                    if (!assignmentsLastUpdatedInHdfs.get().succeeded()) {
                        assignmentsLastUpdatedInHdfs.forceSet(now);
                    }
                    log.info("Wrote {} assignments to hdfs lastHdfsUpdate = lastLocalUpdate ({})", metricToHostMap.size(),
                                    new Date(assignmentsLastUpdatedLocal.get()));
                    // remove metric from metricMap (ArrivalRate) if not being cached
                    metricMap.entrySet().removeIf(e -> !metricToHostMap.containsKey(e.getKey()));
                }
            } finally {
                nonCachedMetricsLocalLock.readLock().unlock();
                balancerLock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            if (writer != null) {
                writer.close();
            }
            try {
                assignmentsIPRWLock.writeLock().release();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    protected void assignMetric(String metric, TimelyBalancedHost tbh) {
        if (isLeader.get()) {
            if (StringUtils.isNotBlank(metric) && tbh != null) {
                balancerLock.writeLock().lock();
                try {
                    metricToHostMap.put(metric, tbh);
                    assignmentsLastUpdatedLocal.set(System.currentTimeMillis());
                } finally {
                    balancerLock.writeLock().unlock();
                }
            } else {
                Exception e = new IllegalStateException("Bad assignment metric:" + metric + " host:" + tbh);
                log.warn(e.getMessage(), e);
            }
        }
    }

    protected void unassignMetric(String metric) {
        if (isLeader.get()) {
            if (StringUtils.isNotBlank(metric)) {
                balancerLock.writeLock().lock();
                try {
                    metricToHostMap.remove(metric);
                    assignmentsLastUpdatedLocal.set(System.currentTimeMillis());
                } finally {
                    balancerLock.writeLock().unlock();
                }
            }
        }
    }
}
