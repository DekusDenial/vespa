// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.google.common.util.concurrent.UncheckedTimeoutException;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Deployer;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.applicationmodel.HostName;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.History;
import com.yahoo.vespa.orchestrator.OrchestrationException;
import com.yahoo.vespa.orchestrator.Orchestrator;
import com.yahoo.yolean.Exceptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maintenance job which deactivates retired nodes, if given permission by orchestrator, or
 * after the system has been given sufficient time to migrate data to other nodes.
 *
 * @author hakon
 */
public class RetiredExpirer extends NodeRepositoryMaintainer {

    private final Deployer deployer;
    private final Metric metric;
    private final Orchestrator orchestrator;
    private final Duration retiredExpiry;

    public RetiredExpirer(NodeRepository nodeRepository,
                          Orchestrator orchestrator,
                          Deployer deployer,
                          Metric metric,
                          Duration maintenanceInterval,
                          Duration retiredExpiry) {
        super(nodeRepository, maintenanceInterval, metric);
        this.deployer = deployer;
        this.metric = metric;
        this.orchestrator = orchestrator;
        this.retiredExpiry = retiredExpiry;
    }

    @Override
    protected boolean maintain() {
        NodeList activeNodes = nodeRepository().nodes().list(Node.State.active);

        Map<ApplicationId, List<Node>> retiredNodesByApplication = activeNodes.stream()
                .filter(node -> node.allocation().isPresent())
                .filter(node -> node.allocation().get().membership().retired())
                .collect(Collectors.groupingBy(node -> node.allocation().get().owner()));

        for (Map.Entry<ApplicationId, List<Node>> entry : retiredNodesByApplication.entrySet()) {
            ApplicationId application = entry.getKey();
            List<Node> retiredNodes = entry.getValue();
            List<Node> nodesToRemove = retiredNodes.stream().filter(this::canRemove).collect(Collectors.toList());
            if (nodesToRemove.isEmpty()) continue;

            try (MaintenanceDeployment deployment = new MaintenanceDeployment(application, deployer, metric, nodeRepository())) {
                if ( ! deployment.isValid()) continue;

                nodeRepository().nodes().setRemovable(application, nodesToRemove);
                boolean success = deployment.activate().isPresent();
                if ( ! success) return success;
                String nodeList = nodesToRemove.stream().map(Node::hostname).collect(Collectors.joining(", "));
                log.info("Redeployed " + application + " to deactivate retired nodes: " +  nodeList);
            }
        }
        return true;
    }

    /**
     * Checks if the node can be removed:
     * if the node is a host, it will only be removed if it has no children,
     * or all its children are parked or failed.
     * Otherwise, a removal is allowed if either of these are true:
     * - The node has been in state {@link History.Event.Type#retired} for longer than {@link #retiredExpiry}
     * - Orchestrator allows it
     */
    private boolean canRemove(Node node) {
        if (node.type().isHost()) {
            if (nodeRepository().nodes().list().childrenOf(node).asList().stream()
                                .allMatch(child -> child.state() == Node.State.parked ||
                                                   child.state() == Node.State.failed)) {
                log.info("Host " + node + " has no non-parked/failed children");
                return true;
            }

            return false;
        }

        if (node.history().hasEventBefore(History.Event.Type.retired, clock().instant().minus(retiredExpiry))) {
            log.warning("Node " + node + " has been retired longer than " + retiredExpiry + ": Allowing removal. This may cause data loss");
            return true;
        }

        try {
            orchestrator.acquirePermissionToRemove(new HostName(node.hostname()));
            log.info("Node " + node + " has been granted permission to be removed");
            return true;
        } catch (UncheckedTimeoutException e) {
            log.warning("Timed out trying to acquire permission to remove " + node.hostname() + ": " + Exceptions.toMessageString(e));
            return false;
        } catch (OrchestrationException e) {
            log.info("Did not get permission to remove retired " + node + ": " + Exceptions.toMessageString(e));
            return false;
        }
    }

}
