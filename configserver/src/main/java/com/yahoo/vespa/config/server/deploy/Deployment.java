// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.deploy;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.model.api.ServiceInfo;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.Provisioner;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.ApplicationRepository.ActionTimer;
import com.yahoo.vespa.config.server.ApplicationRepository.Activation;
import com.yahoo.vespa.config.server.TimeoutBudget;
import com.yahoo.vespa.config.server.application.ApplicationReindexing;
import com.yahoo.vespa.config.server.configchange.ConfigChangeActions;
import com.yahoo.vespa.config.server.configchange.ReindexActions;
import com.yahoo.vespa.config.server.configchange.RestartActions;
import com.yahoo.vespa.config.server.http.InternalServerException;
import com.yahoo.vespa.config.server.session.PrepareParams;
import com.yahoo.vespa.config.server.session.Session;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.curator.Lock;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * The process of deploying an application.
 * Deployments are created by an {@link ApplicationRepository}.
 * Instances of this are not multithread safe.
 *
 * @author Ulf Lilleengen
 * @author bratseth
 */
public class Deployment implements com.yahoo.config.provision.Deployment {

    private static final Logger log = Logger.getLogger(Deployment.class.getName());

    /** The session containing the application instance to activate */
    private final Session session;
    private final ApplicationRepository applicationRepository;
    private final Supplier<PrepareParams> params;
    private final Optional<Provisioner> provisioner;
    private final Tenant tenant;
    private final DeployLogger deployLogger;
    private final Clock clock;
    private final boolean internalRedeploy;

    private boolean prepared;
    private ConfigChangeActions configChangeActions;

    private Deployment(Session session, ApplicationRepository applicationRepository, Supplier<PrepareParams> params,
                       Optional<Provisioner> provisioner, Tenant tenant, DeployLogger deployLogger, Clock clock,
                       boolean internalRedeploy, boolean prepared) {
        this.session = session;
        this.applicationRepository = applicationRepository;
        this.params = params;
        this.provisioner = provisioner;
        this.tenant = tenant;
        this.deployLogger = deployLogger;
        this.clock = clock;
        this.internalRedeploy = internalRedeploy;
        this.prepared = prepared;
    }

    public static Deployment unprepared(Session session, ApplicationRepository applicationRepository,
                                        Optional<Provisioner> provisioner, Tenant tenant, PrepareParams params, DeployLogger logger, Clock clock) {
        return new Deployment(session, applicationRepository, () -> params, provisioner, tenant, logger, clock, false, false);
    }

    public static Deployment unprepared(Session session, ApplicationRepository applicationRepository,
                                        Optional<Provisioner> provisioner, Tenant tenant, DeployLogger logger,
                                        Duration timeout, Clock clock, boolean validate, boolean isBootstrap) {
        Supplier<PrepareParams> params = createPrepareParams(clock, timeout, session, isBootstrap, !validate, false);
        return new Deployment(session, applicationRepository, params, provisioner, tenant, logger, clock, true, false);
    }

    public static Deployment prepared(Session session, ApplicationRepository applicationRepository,
                                      Optional<Provisioner> provisioner, Tenant tenant, DeployLogger logger,
                                      Duration timeout, Clock clock, boolean isBootstrap, boolean force) {
        Supplier<PrepareParams> params = createPrepareParams(clock, timeout, session, isBootstrap, false, force);
        return new Deployment(session, applicationRepository, params, provisioner, tenant, logger, clock, false, true);
    }

    /** Prepares this. This does nothing if this is already prepared */
    @Override
    public void prepare() {
        if (prepared) return;
        PrepareParams params = this.params.get();
        ApplicationId applicationId = params.getApplicationId();
        try (ActionTimer timer = applicationRepository.timerFor(applicationId, "deployment.prepareMillis")) {
            this.configChangeActions = tenant.getSessionRepository().prepareLocalSession(session, deployLogger, params, clock.instant());
            this.prepared = true;
        }
    }

    /** Activates this. If it is not already prepared, this will call prepare first. */
    @Override
    public long activate() {
        prepare();

        validateSessionStatus(session);
        PrepareParams params = this.params.get();
        ApplicationId applicationId = session.getApplicationId();
        try (ActionTimer timer = applicationRepository.timerFor(applicationId, "deployment.activateMillis")) {
            TimeoutBudget timeoutBudget = params.getTimeoutBudget();
            timeoutBudget.assertNotTimedOut(() -> "Timeout exceeded when trying to activate '" + applicationId + "'");

            Activation activation;
            try {
                activation = applicationRepository.activate(session, applicationId, tenant, params.force());
            }
            catch (RuntimeException e) {
                throw e;
            }
            catch (Exception e) {
                throw new InternalServerException("Error when activating '" + applicationId + "'", e);
            }

            activation.awaitCompletion(timeoutBudget.timeLeft());
            log.log(Level.INFO, session.logPre() + "Session " + session.getSessionId() + " activated successfully using " +
                                provisioner.map(provisioner -> provisioner.getClass().getSimpleName()).orElse("no host provisioner") +
                                ". Config generation " + session.getMetaData().getGeneration() +
                                activation.sourceSessionId().stream().mapToObj(id -> ". Based on session " + id).findFirst().orElse("") +
                                ". File references: " + applicationRepository.getFileReferences(applicationId));

            if (configChangeActions != null) {
                if (provisioner.isPresent())
                    restartServices(applicationId);

                storeReindexing(applicationId, session.getMetaData().getGeneration());
            }

            return session.getMetaData().getGeneration();
        }
    }

    private void restartServices(ApplicationId applicationId) {
        RestartActions restartActions = configChangeActions.getRestartActions().useForInternalRestart(internalRedeploy);

        if ( ! restartActions.isEmpty()) {
            Set<String> hostnames = restartActions.getEntries().stream()
                                                  .flatMap(entry -> entry.getServices().stream())
                                                  .map(ServiceInfo::getHostName)
                                                  .collect(Collectors.toUnmodifiableSet());

            provisioner.get().restart(applicationId, HostFilter.from(hostnames, Set.of(), Set.of(), Set.of()));
            deployLogger.log(Level.INFO, String.format("Scheduled service restart of %d nodes: %s",
                                                       hostnames.size(), hostnames.stream().sorted().collect(Collectors.joining(", "))));

            this.configChangeActions = new ConfigChangeActions(
                    new RestartActions(), configChangeActions.getRefeedActions(), configChangeActions.getReindexActions());
        }
    }

    private void storeReindexing(ApplicationId applicationId, long requiredSession) {
        try (Lock sessionLock = tenant.getApplicationRepo().lock(applicationId)) {
            ApplicationReindexing reindexing = tenant.getApplicationRepo().database().readReindexingStatus(applicationId)
                                                     .orElse(ApplicationReindexing.ready(clock.instant()));

            for (ReindexActions.Entry entry : configChangeActions.getReindexActions().getEntries())
                reindexing = reindexing.withPending(entry.getClusterName(), entry.getDocumentType(), requiredSession);

            tenant.getApplicationRepo().database().writeReindexingStatus(applicationId, reindexing);
        }
    }

    /**
     * Request a restart of services of this application on hosts matching the filter.
     * This is sometimes needed after activation, but can also be requested without
     * doing prepare and activate in the same session.
     */
    @Override
    public void restart(HostFilter filter) {
        provisioner.get().restart(session.getApplicationId(), filter);
    }

    /** Exposes the session of this for testing only */
    public Session session() { return session; }

    /**
     * @return config change actions that need to be performed as result of prepare
     * @throws IllegalArgumentException if called without being prepared by this
     */
    public ConfigChangeActions configChangeActions() {
        if (configChangeActions != null) return configChangeActions;
        throw new IllegalArgumentException("No config change actions: " + (prepared ? "was already prepared" : "not yet prepared"));
    }

    private void validateSessionStatus(Session session) {
        long sessionId = session.getSessionId();
        if (Session.Status.NEW.equals(session.getStatus())) {
            throw new IllegalStateException(session.logPre() + "Session " + sessionId + " is not prepared");
        } else if (Session.Status.ACTIVATE.equals(session.getStatus())) {
            throw new IllegalStateException(session.logPre() + "Session " + sessionId + " is already active");
        }
    }

    /**
     * @param clock system clock
     * @param timeout total timeout duration of prepare + activate
     * @param session the local session for this deployment
     * @param isBootstrap true if this deployment is done to bootstrap the config server
     * @param ignoreValidationErrors whether this model should be validated
     * @param force whether activation of this model should be forced
     */
    private static Supplier<PrepareParams> createPrepareParams(
            Clock clock, Duration timeout, Session session,
            boolean isBootstrap, boolean ignoreValidationErrors, boolean force) {

        // Supplier because shouldn't/cant create this before validateSessionStatus() for prepared deployments
        // memoized because we want to create this once for unprepared deployments
        return Suppliers.memoize(() -> {
            TimeoutBudget timeoutBudget = new TimeoutBudget(clock, timeout);

            PrepareParams.Builder params = new PrepareParams.Builder()
                    .applicationId(session.getApplicationId())
                    .vespaVersion(session.getVespaVersion().toString())
                    .timeoutBudget(timeoutBudget)
                    .ignoreValidationErrors(ignoreValidationErrors)
                    .isBootstrap(isBootstrap)
                    .force(force);
            session.getDockerImageRepository().ifPresent(params::dockerImageRepository);
            session.getAthenzDomain().ifPresent(params::athenzDomain);

            return params.build();
        });
    }

}
