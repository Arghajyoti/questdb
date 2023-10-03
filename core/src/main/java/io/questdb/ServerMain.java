/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.ColumnIndexerJob;
import io.questdb.cairo.O3Utils;
import io.questdb.cairo.security.ReadOnlySecurityContextFactory;
import io.questdb.cairo.security.SecurityContextFactory;
import io.questdb.cairo.wal.ApplyWal2TableJob;
import io.questdb.cairo.wal.CheckWalTransactionsJob;
import io.questdb.cairo.wal.WalPurgeJob;
import io.questdb.cutlass.Services;
import io.questdb.cutlass.auth.AuthUtils;
import io.questdb.cutlass.auth.DefaultLineAuthenticatorFactory;
import io.questdb.cutlass.auth.EllipticCurveAuthenticatorFactory;
import io.questdb.cutlass.auth.LineAuthenticatorFactory;
import io.questdb.cutlass.http.HttpContextConfiguration;
import io.questdb.cutlass.line.tcp.StaticChallengeResponseMatcher;
import io.questdb.cutlass.pgwire.*;
import io.questdb.cutlass.text.CopyJob;
import io.questdb.cutlass.text.CopyRequestJob;
import io.questdb.griffin.engine.groupby.vect.GroupByJob;
import io.questdb.griffin.engine.table.AsyncFilterAtom;
import io.questdb.griffin.engine.table.LatestByAllIndexedJob;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.WorkerPool;
import io.questdb.std.CharSequenceObjHashMap;
import io.questdb.std.Chars;
import io.questdb.std.str.DirectByteCharSink;

import java.io.Closeable;
import java.io.File;
import java.security.PublicKey;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerMain implements Closeable {
    private final String banner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ServerConfiguration config;
    private final CairoEngine engine;
    private final FreeOnExit freeOnExit = new FreeOnExit();
    private final Log log;
    private final Metrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();
    private boolean initialized;
    private WorkerPoolManager workerPoolManager;

    public ServerMain(String... args) {
        this(new Bootstrap(args));
    }

    public ServerMain(final Bootstrap bootstrap) {
        this.config = bootstrap.getConfiguration();
        this.log = bootstrap.getLog();
        this.banner = bootstrap.getBanner();
        this.metrics = bootstrap.getMetrics();

        // create cairo engine
        engine = freeOnExit.register(bootstrap.newCairoEngine());
        config.init(engine, freeOnExit);
        freeOnExit.register(config.getFactoryProvider());
        engine.load();
    }

    public static LineAuthenticatorFactory getLineAuthenticatorFactory(ServerConfiguration configuration) {
        LineAuthenticatorFactory authenticatorFactory;
        // create default authenticator for Line TCP protocol
        if (configuration.getLineTcpReceiverConfiguration().isEnabled() && configuration.getLineTcpReceiverConfiguration().getAuthDB() != null) {
            // we need "root/" here, not "root/db/"
            final String rootDir = new File(configuration.getCairoConfiguration().getRoot()).getParent();
            final String absPath = new File(rootDir, configuration.getLineTcpReceiverConfiguration().getAuthDB()).getAbsolutePath();
            CharSequenceObjHashMap<PublicKey> authDb = AuthUtils.loadAuthDb(absPath);
            authenticatorFactory = new EllipticCurveAuthenticatorFactory(() -> new StaticChallengeResponseMatcher(authDb));
        } else {
            authenticatorFactory = DefaultLineAuthenticatorFactory.INSTANCE;
        }
        return authenticatorFactory;
    }

    public static PgWireAuthenticatorFactory getPgWireAuthenticatorFactory(ServerConfiguration configuration, DirectByteCharSink defaultUserPasswordSink, DirectByteCharSink readOnlyUserPasswordSink) {
        UsernamePasswordMatcher usernamePasswordMatcher = newPgWireUsernamePasswordMatcher(configuration.getPGWireConfiguration(), defaultUserPasswordSink, readOnlyUserPasswordSink);
        return new UsernamePasswordPgWireAuthenticatorFactory(usernamePasswordMatcher);
    }

    public static SecurityContextFactory getSecurityContextFactory(ServerConfiguration configuration) {
        boolean readOnlyInstance = configuration.getCairoConfiguration().isReadOnlyInstance()
                || configuration.isReadOnlyReplica();
        if (readOnlyInstance) {
            return ReadOnlySecurityContextFactory.INSTANCE;
        } else {
            PGWireConfiguration pgWireConfiguration = configuration.getPGWireConfiguration();
            HttpContextConfiguration httpContextConfiguration = configuration.getHttpServerConfiguration().getHttpContextConfiguration();
            boolean pgWireReadOnlyContext = pgWireConfiguration.readOnlySecurityContext();
            boolean pgWireReadOnlyUserEnabled = pgWireConfiguration.isReadOnlyUserEnabled();
            String pgWireReadOnlyUsername = pgWireReadOnlyUserEnabled ? pgWireConfiguration.getReadOnlyUsername() : null;
            boolean httpReadOnly = httpContextConfiguration.readOnlySecurityContext();
            return new ReadOnlyUsersAwareSecurityContextFactory(pgWireReadOnlyContext, pgWireReadOnlyUsername, httpReadOnly);
        }
    }

    public static void main(String[] args) {
        try {
            new ServerMain(args).start(true);
        } catch (Throwable thr) {
            thr.printStackTrace();
            LogFactory.closeInstance();
            System.exit(55);
        }
    }

    public static UsernamePasswordMatcher newPgWireUsernamePasswordMatcher(PGWireConfiguration configuration, DirectByteCharSink defaultUserPasswordSink, DirectByteCharSink readOnlyUserPasswordSink) {
        String defaultUsername = configuration.getDefaultUsername();
        String defaultPassword = configuration.getDefaultPassword();
        boolean defaultUserEnabled = !Chars.empty(defaultUsername) && !Chars.empty(defaultPassword);

        String readOnlyUsername = configuration.getReadOnlyUsername();
        String readOnlyPassword = configuration.getReadOnlyPassword();
        boolean readOnlyUserValid = !Chars.empty(readOnlyUsername) && !Chars.empty(readOnlyPassword);
        boolean readOnlyUserEnabled = configuration.isReadOnlyUserEnabled() && readOnlyUserValid;

        if (defaultUserEnabled && readOnlyUserEnabled) {
            defaultUserPasswordSink.encodeUtf8(defaultPassword);
            readOnlyUserPasswordSink.encodeUtf8(readOnlyPassword);

            return new CombiningUsernamePasswordMatcher(
                    new StaticUsernamePasswordMatcher(defaultUsername, defaultUserPasswordSink.getPtr(), defaultUserPasswordSink.length()),
                    new StaticUsernamePasswordMatcher(readOnlyUsername, readOnlyUserPasswordSink.getPtr(), readOnlyUserPasswordSink.length())
            );
        } else if (defaultUserEnabled) {
            defaultUserPasswordSink.encodeUtf8(defaultPassword);
            return new StaticUsernamePasswordMatcher(defaultUsername, defaultUserPasswordSink.getPtr(), defaultUserPasswordSink.length());
        } else if (readOnlyUserEnabled) {
            readOnlyUserPasswordSink.encodeUtf8(readOnlyPassword);
            return new StaticUsernamePasswordMatcher(readOnlyUsername, readOnlyUserPasswordSink.getPtr(), readOnlyUserPasswordSink.length());
        } else {
            return NeverMatchUsernamePasswordMatcher.INSTANCE;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (initialized) {
                workerPoolManager.halt();
            }
            freeOnExit.close();
        }
    }

    public ServerConfiguration getConfiguration() {
        return config;
    }

    public CairoEngine getEngine() {
        if (closed.get()) {
            throw new IllegalStateException("close was called");
        }
        return engine;
    }

    public WorkerPoolManager getWorkerPoolManager() {
        if (closed.get()) {
            throw new IllegalStateException("close was called");
        }
        return workerPoolManager;
    }

    public boolean hasBeenClosed() {
        return closed.get();
    }

    public boolean hasStarted() {
        return running.get();
    }

    public void postInitialize() {
        // no-op
    }

    public synchronized void start(boolean addShutdownHook) {
        if (!closed.get() && running.compareAndSet(false, true)) {
            initialize();
            postInitialize();

            if (addShutdownHook) {
                addShutdownHook();
            }
            workerPoolManager.start(log);
            Bootstrap.logWebConsoleUrls(config, log, banner);
            System.gc(); // final GC
            log.advisoryW().$("enjoy").$();
        }
    }

    public void start() {
        start(false);
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.err.println("QuestDB is shutting down...");
                System.err.println("Pre-touch magic number: " + AsyncFilterAtom.PRE_TOUCH_BLACK_HOLE.sum());
                close();
                LogFactory.closeInstance();
            } catch (Error ignore) {
                // ignore
            } finally {
                System.err.println("QuestDB is shutdown.");
            }
        }));
    }

    private synchronized void initialize() {
        initialized = true;

        // create the worker pool manager, and configure the shared pool
        final boolean walSupported = config.getCairoConfiguration().isWalSupported();
        final boolean isReadOnly = config.getCairoConfiguration().isReadOnlyInstance();
        final boolean walApplyEnabled = config.getCairoConfiguration().isWalApplyEnabled();
        final CairoConfiguration cairoConfig = config.getCairoConfiguration();
        workerPoolManager = new WorkerPoolManager(config, metrics.health()) {
            @Override
            protected void configureSharedPool(WorkerPool sharedPool) {
                try {
                    sharedPool.assign(engine.getEngineMaintenanceJob());

                    final MessageBus messageBus = engine.getMessageBus();
                    // register jobs that help parallel execution of queries and column indexing.
                    sharedPool.assign(new ColumnIndexerJob(messageBus));
                    sharedPool.assign(new GroupByJob(messageBus));
                    sharedPool.assign(new LatestByAllIndexedJob(messageBus));

                    if (!isReadOnly) {
                        O3Utils.setupWorkerPool(
                                sharedPool,
                                engine,
                                config.getCairoConfiguration().getCircuitBreakerConfiguration()
                        );

                        if (walSupported) {
                            sharedPool.assign(new CheckWalTransactionsJob(engine));
                            final WalPurgeJob walPurgeJob = new WalPurgeJob(engine);
                            engine.setWalPurgeJobRunLock(walPurgeJob.getRunLock());
                            walPurgeJob.delayByHalfInterval();
                            sharedPool.assign(walPurgeJob);
                            sharedPool.freeOnExit(walPurgeJob);

                            if (walApplyEnabled && !config.getWalApplyPoolConfiguration().isEnabled()) {
                                setupWalApplyJob(sharedPool, engine, getSharedWorkerCount());
                            }
                        }

                        // text import
                        CopyJob.assignToPool(messageBus, sharedPool);
                        if (cairoConfig.getSqlCopyInputRoot() != null) {
                            final CopyRequestJob copyRequestJob = new CopyRequestJob(
                                    engine,
                                    // save CPU resources for collecting and processing jobs
                                    Math.max(1, sharedPool.getWorkerCount() - 2)
                            );
                            sharedPool.assign(copyRequestJob);
                            sharedPool.freeOnExit(copyRequestJob);
                        }
                    }

                    // telemetry
                    if (!cairoConfig.getTelemetryConfiguration().getDisableCompletely()) {
                        final TelemetryJob telemetryJob = new TelemetryJob(engine);
                        freeOnExit.register(telemetryJob);
                        if (cairoConfig.getTelemetryConfiguration().getEnabled()) {
                            sharedPool.assign(telemetryJob);
                        }
                    }
                } catch (Throwable thr) {
                    throw new Bootstrap.BootstrapException(thr);
                }
            }
        };

        if (walApplyEnabled && !isReadOnly && walSupported && config.getWalApplyPoolConfiguration().isEnabled()) {
            WorkerPool walApplyWorkerPool = workerPoolManager.getInstance(
                    config.getWalApplyPoolConfiguration(),
                    metrics.health(),
                    WorkerPoolManager.Requester.WAL_APPLY
            );
            setupWalApplyJob(walApplyWorkerPool, engine, workerPoolManager.getSharedWorkerCount());
        }

        // http
        freeOnExit.register(Services.createHttpServer(
                config.getHttpServerConfiguration(),
                engine,
                workerPoolManager,
                metrics
        ));

        // http min
        freeOnExit.register(Services.createMinHttpServer(
                config.getHttpMinServerConfiguration(),
                engine,
                workerPoolManager,
                metrics
        ));

        // pg wire
        freeOnExit.register(Services.createPGWireServer(
                config.getPGWireConfiguration(),
                engine,
                workerPoolManager,
                metrics
        ));

        if (!isReadOnly && !config.isReadOnlyReplica()) {
            // ilp/tcp
            freeOnExit.register(Services.createLineTcpReceiver(
                    config.getLineTcpReceiverConfiguration(),
                    engine,
                    workerPoolManager,
                    metrics
            ));

            // ilp/udp
            freeOnExit.register(Services.createLineUdpReceiver(
                    config.getLineUdpReceiverConfiguration(),
                    engine,
                    workerPoolManager
            ));
        }

        System.gc(); // GC 1
        log.advisoryW().$("server is ready to be started").$();
    }

    protected void setupWalApplyJob(
            WorkerPool workerPool,
            CairoEngine engine,
            int sharedWorkerCount
    ) {
        for (int i = 0, workerCount = workerPool.getWorkerCount(); i < workerCount; i++) {
            // create job per worker
            final ApplyWal2TableJob applyWal2TableJob = new ApplyWal2TableJob(engine, workerCount, sharedWorkerCount);
            workerPool.assign(i, applyWal2TableJob);
            workerPool.freeOnExit(applyWal2TableJob);
        }
    }
}
