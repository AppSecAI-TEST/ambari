/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.state.svccomphost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.agent.AlertDefinitionCommand;
import org.apache.ambari.server.controller.ServiceComponentHostResponse;
import org.apache.ambari.server.events.AlertHashInvalidationEvent;
import org.apache.ambari.server.events.MaintenanceModeEvent;
import org.apache.ambari.server.events.ServiceComponentInstalledEvent;
import org.apache.ambari.server.events.ServiceComponentUninstalledEvent;
import org.apache.ambari.server.events.publishers.AmbariEventPublisher;
import org.apache.ambari.server.orm.dao.HostComponentDesiredStateDAO;
import org.apache.ambari.server.orm.dao.HostComponentStateDAO;
import org.apache.ambari.server.orm.dao.HostDAO;
import org.apache.ambari.server.orm.dao.HostVersionDAO;
import org.apache.ambari.server.orm.dao.RepositoryVersionDAO;
import org.apache.ambari.server.orm.dao.ServiceComponentDesiredStateDAO;
import org.apache.ambari.server.orm.entities.HostComponentDesiredStateEntity;
import org.apache.ambari.server.orm.entities.HostComponentDesiredStateEntityPK;
import org.apache.ambari.server.orm.entities.HostComponentStateEntity;
import org.apache.ambari.server.orm.entities.HostComponentStateEntityPK;
import org.apache.ambari.server.orm.entities.HostEntity;
import org.apache.ambari.server.orm.entities.HostVersionEntity;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.ServiceComponentDesiredStateEntity;
import org.apache.ambari.server.orm.entities.ServiceComponentDesiredStateEntityPK;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.HostComponentAdminState;
import org.apache.ambari.server.state.HostConfig;
import org.apache.ambari.server.state.HostState;
import org.apache.ambari.server.state.MaintenanceState;
import org.apache.ambari.server.state.RepositoryVersionState;
import org.apache.ambari.server.state.SecurityState;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.ServiceComponentHostEvent;
import org.apache.ambari.server.state.ServiceComponentHostEventType;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.state.UpgradeState;
import org.apache.ambari.server.state.alert.AlertDefinitionHash;
import org.apache.ambari.server.state.configgroup.ConfigGroup;
import org.apache.ambari.server.state.fsm.InvalidStateTransitionException;
import org.apache.ambari.server.state.fsm.SingleArcTransition;
import org.apache.ambari.server.state.fsm.StateMachine;
import org.apache.ambari.server.state.fsm.StateMachineFactory;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.google.inject.persist.Transactional;

public class ServiceComponentHostImpl implements ServiceComponentHost {

  private static final Logger LOG =
      LoggerFactory.getLogger(ServiceComponentHostImpl.class);

  // FIXME need more debug logs

  private final ReadWriteLock clusterGlobalLock;
  private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
  private final Lock readLock = readWriteLock.readLock();
  private final Lock writeLock = readWriteLock.writeLock();

  private final ServiceComponent serviceComponent;
  private final Host host;
  private boolean persisted = false;

  @Inject
  Gson gson;
  @Inject
  HostComponentStateDAO hostComponentStateDAO;
  @Inject
  HostComponentDesiredStateDAO hostComponentDesiredStateDAO;
  @Inject
  HostDAO hostDAO;
  @Inject
  HostVersionDAO hostVersionDAO;
  @Inject
  RepositoryVersionDAO repositoryVersionDAO;
  @Inject
  ServiceComponentDesiredStateDAO serviceComponentDesiredStateDAO;
  @Inject
  Clusters clusters;
  @Inject
  ConfigHelper helper;

  /**
   * Used for creating commands to send to the agents when alert definitions are
   * added as the result of a service install.
   */
  @Inject
  private AlertDefinitionHash alertDefinitionHash;

  /**
   * Used to publish events relating to service CRUD operations.
   */
  @Inject
  private AmbariEventPublisher eventPublisher;

  private HostComponentStateEntity stateEntity;
  private HostComponentDesiredStateEntity desiredStateEntity;

  private long lastOpStartTime;
  private long lastOpEndTime;
  private long lastOpLastUpdateTime;
  private Map<String, HostConfig> actualConfigs = new HashMap<String,
    HostConfig>();
  private List<Map<String, String>> processes = new ArrayList<Map<String, String>>();

  private static final StateMachineFactory
  <ServiceComponentHostImpl, State,
  ServiceComponentHostEventType, ServiceComponentHostEvent>
    daemonStateMachineFactory
      = new StateMachineFactory<ServiceComponentHostImpl,
          State, ServiceComponentHostEventType,
          ServiceComponentHostEvent>
          (State.INIT)

  // define the state machine of a HostServiceComponent for runnable
  // components

     .addTransition(State.INIT,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())

  .addTransition(State.INSTALLING, State.INSTALLED,
      ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
      new AlertDefinitionCommandTransition())

     .addTransition(State.INSTALLED,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLING,
         State.INSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

       // Allow transition on abort
     .addTransition(State.INSTALL_FAILED, State.INSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALLED,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_START,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_STOP,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
          State.INSTALLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
          new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.INSTALLED,
          State.STARTED,
          ServiceComponentHostEventType.HOST_SVCCOMP_STARTED,
          new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.INSTALLED,
          State.INSTALLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_STOPPED,
          new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.STARTING,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())

     .addTransition(State.STARTING,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_START,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.STARTING,
         State.STARTED,
         ServiceComponentHostEventType.HOST_SVCCOMP_STARTED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.STARTING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALLED,
         State.STARTING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.STARTED,
          State.STARTED,
          ServiceComponentHostEventType.HOST_SVCCOMP_STARTED,
          new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.STARTED,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_STOP,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.STARTED,
          State.STARTED,
          ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
          new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.STARTED,
          State.INSTALLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_STOPPED,
          new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.STOPPING,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.STOPPING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_STOPPED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.STOPPING,
         State.STARTED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.STARTED,
         State.STOPPING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UPGRADING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpInProgressTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.WIPING_OUT,
         State.INIT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

      .addTransition(State.INSTALLED,
          State.DISABLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_DISABLE,
          new ServiceComponentHostOpCompletedTransition())
      .addTransition(State.DISABLED,
          State.DISABLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_DISABLE,
          new ServiceComponentHostOpCompletedTransition())
      .addTransition(State.UNKNOWN,
                  State.DISABLED,
                  ServiceComponentHostEventType.HOST_SVCCOMP_DISABLE,
                  new ServiceComponentHostOpCompletedTransition())
      .addTransition(State.INSTALL_FAILED,
                  State.DISABLED,
                  ServiceComponentHostEventType.HOST_SVCCOMP_DISABLE,
                  new ServiceComponentHostOpCompletedTransition())

      .addTransition(State.DISABLED,
          State.INSTALLED,
          ServiceComponentHostEventType.HOST_SVCCOMP_RESTORE,
          new ServiceComponentHostOpCompletedTransition())


     .installTopology();

  private static final StateMachineFactory
  <ServiceComponentHostImpl, State,
  ServiceComponentHostEventType, ServiceComponentHostEvent>
    clientStateMachineFactory
      = new StateMachineFactory<ServiceComponentHostImpl,
          State, ServiceComponentHostEventType,
          ServiceComponentHostEvent>
          (State.INIT)

  // define the state machine of a HostServiceComponent for client only
  // components

     .addTransition(State.INIT,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.INSTALLING,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.INSTALLED,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.INSTALLING,
         State.INSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALL_FAILED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())
      // Allow transition on abort
     .addTransition(State.INSTALL_FAILED, State.INSTALL_FAILED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

    .addTransition(State.INSTALLED,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.INSTALLED,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.INSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())
    .addTransition(State.INSTALLED,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UPGRADING,
         State.INSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.UPGRADING,
         State.UPGRADING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UPGRADE,
         new ServiceComponentHostOpInProgressTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLED,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.UNINSTALLING,
         State.UNINSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_UNINSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.INSTALLING,
         ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.UNINSTALLED,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_IN_PROGRESS,
         new ServiceComponentHostOpInProgressTransition())
     .addTransition(State.WIPING_OUT,
         State.INIT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED,
         new ServiceComponentHostOpCompletedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_FAILED,
         new ServiceComponentHostOpCompletedTransition())

     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_OP_RESTART,
         new ServiceComponentHostOpStartedTransition())
     .addTransition(State.WIPING_OUT,
         State.WIPING_OUT,
         ServiceComponentHostEventType.HOST_SVCCOMP_WIPEOUT,
         new ServiceComponentHostOpStartedTransition())

     .installTopology();

  private final StateMachine<State,
      ServiceComponentHostEventType, ServiceComponentHostEvent> stateMachine;

  static class ServiceComponentHostOpCompletedTransition
     implements SingleArcTransition<ServiceComponentHostImpl,
         ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      // TODO Audit logs
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());

    }

  }

  /**
   * The {@link AlertDefinitionCommandTransition} is used to capture the
   * transition from {@link State#INSTALLING} to {@link State#INSTALLED} so that
   * the host affected will have new {@link AlertDefinitionCommand}s pushed to
   * it.
   */
  static class AlertDefinitionCommandTransition implements
      SingleArcTransition<ServiceComponentHostImpl, ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      if (event.getType() != ServiceComponentHostEventType.HOST_SVCCOMP_OP_SUCCEEDED) {
        return;
      }

      // invalidate the host
      String hostName = impl.getHostName();
      impl.alertDefinitionHash.invalidate(impl.getClusterName(), hostName);

      // publish the event
      AlertHashInvalidationEvent hashInvalidationEvent = new AlertHashInvalidationEvent(
          impl.getClusterId(), Collections.singletonList(hostName));

      impl.eventPublisher.publish(hashInvalidationEvent);
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());

    }
  }

  static class ServiceComponentHostOpStartedTransition
    implements SingleArcTransition<ServiceComponentHostImpl,
        ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      // TODO Audit logs
      // FIXME handle restartOp event
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());
      if (event.getType() ==
          ServiceComponentHostEventType.HOST_SVCCOMP_INSTALL) {
        ServiceComponentHostInstallEvent e =
            (ServiceComponentHostInstallEvent) event;
        if (LOG.isDebugEnabled()) {
          LOG.debug("Updating live stack version during INSTALL event"
              + ", new stack version=" + e.getStackId());
        }
        impl.setStackVersion(new StackId(e.getStackId()));
      }
    }
  }

  static class ServiceComponentHostOpInProgressTransition
    implements SingleArcTransition<ServiceComponentHostImpl,
        ServiceComponentHostEvent> {

    @Override
    public void transition(ServiceComponentHostImpl impl,
        ServiceComponentHostEvent event) {
      // TODO Audit logs
      impl.updateLastOpInfo(event.getType(), event.getOpTimestamp());
    }
  }


  private void resetLastOpInfo() {
    clusterGlobalLock.readLock().lock();
    try {
      try {
        writeLock.lock();
        setLastOpStartTime(-1);
        setLastOpLastUpdateTime(-1);
        setLastOpEndTime(-1);
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  private void updateLastOpInfo(ServiceComponentHostEventType eventType,
      long time) {
    clusterGlobalLock.readLock().lock();
    try {
      try {
        writeLock.lock();
        switch (eventType) {
          case HOST_SVCCOMP_INSTALL:
          case HOST_SVCCOMP_START:
          case HOST_SVCCOMP_STOP:
          case HOST_SVCCOMP_UNINSTALL:
          case HOST_SVCCOMP_WIPEOUT:
          case HOST_SVCCOMP_OP_RESTART:
            resetLastOpInfo();
            setLastOpStartTime(time);
            break;
          case HOST_SVCCOMP_OP_FAILED:
          case HOST_SVCCOMP_OP_SUCCEEDED:
          case HOST_SVCCOMP_STOPPED:
          case HOST_SVCCOMP_STARTED:
            setLastOpLastUpdateTime(time);
            setLastOpEndTime(time);
            break;
          case HOST_SVCCOMP_OP_IN_PROGRESS:
            setLastOpLastUpdateTime(time);
            break;
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @AssistedInject
  public ServiceComponentHostImpl(@Assisted ServiceComponent serviceComponent,
                                  @Assisted String hostName, Injector injector) {
    injector.injectMembers(this);

    if (serviceComponent.isClientComponent()) {
      stateMachine = clientStateMachineFactory.make(this);
    } else {
      stateMachine = daemonStateMachineFactory.make(this);
    }

    this.serviceComponent = serviceComponent;
    clusterGlobalLock = serviceComponent.getClusterGlobalLock();

    stateEntity = new HostComponentStateEntity();
    stateEntity.setClusterId(serviceComponent.getClusterId());
    stateEntity.setComponentName(serviceComponent.getName());
    stateEntity.setServiceName(serviceComponent.getServiceName());
    stateEntity.setVersion("UNKNOWN");
    stateEntity.setHostName(hostName);
    stateEntity.setCurrentState(stateMachine.getCurrentState());
    stateEntity.setUpgradeState(UpgradeState.NONE);
    stateEntity.setCurrentStackVersion(gson.toJson(new StackId()));

    desiredStateEntity = new HostComponentDesiredStateEntity();
    desiredStateEntity.setClusterId(serviceComponent.getClusterId());
    desiredStateEntity.setComponentName(serviceComponent.getName());
    desiredStateEntity.setServiceName(serviceComponent.getServiceName());
    desiredStateEntity.setHostName(hostName);
    desiredStateEntity.setDesiredState(State.INIT);
    desiredStateEntity.setDesiredStackVersion(
        gson.toJson(serviceComponent.getDesiredStackVersion()));
    if(!serviceComponent.isMasterComponent() && !serviceComponent.isClientComponent()) {
      desiredStateEntity.setAdminState(HostComponentAdminState.INSERVICE);
    } else {
      desiredStateEntity.setAdminState(null);
    }

    try {
      host = clusters.getHost(hostName);
    } catch (AmbariException e) {
      //TODO exception?
      LOG.error("Host '{}' was not found" + hostName);
      throw new RuntimeException(e);
    }

    resetLastOpInfo();
  }

  @AssistedInject
  public ServiceComponentHostImpl(@Assisted ServiceComponent serviceComponent,
                                  @Assisted HostComponentStateEntity stateEntity,
                                  @Assisted HostComponentDesiredStateEntity desiredStateEntity,
                                  Injector injector) {
    injector.injectMembers(this);
    this.serviceComponent = serviceComponent;
    clusterGlobalLock = serviceComponent.getClusterGlobalLock();

    this.desiredStateEntity = desiredStateEntity;
    this.stateEntity = stateEntity;

    //TODO implement State Machine init as now type choosing is hardcoded in above code
    if (serviceComponent.isClientComponent()) {
      stateMachine = clientStateMachineFactory.make(this);
    } else {
      stateMachine = daemonStateMachineFactory.make(this);
    }
    stateMachine.setCurrentState(stateEntity.getCurrentState());

    try {
      host = clusters.getHost(stateEntity.getHostName());
    } catch (AmbariException e) {
      //TODO exception? impossible due to database restrictions
      LOG.error("Host '{}' was not found " + stateEntity.getHostName());
      throw new RuntimeException(e);
    }

    persisted = true;
  }

  @Override
  public State getState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return stateMachine.getCurrentState();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void setState(State state) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        stateMachine.setCurrentState(state);
        stateEntity.setCurrentState(state);
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public String getVersion() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return stateEntity.getVersion();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setVersion(String version) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        stateEntity.setVersion(version);
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public SecurityState getSecurityState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return stateEntity.getSecurityState();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setSecurityState(SecurityState securityState) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        stateEntity.setSecurityState(securityState);
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public SecurityState getDesiredSecurityState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return desiredStateEntity.getSecurityState();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setDesiredSecurityState(SecurityState securityState) throws AmbariException {
    if(!securityState.isEndpoint()) {
      throw new AmbariException("The security state must be an endpoint state");
    }

    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        desiredStateEntity.setSecurityState(securityState);
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  /***
   * To be called during the upgrade of a specific Component in a host.
   * The potential upgrade states are NONE (default), PENDING, IN_PROGRESS, FAILED.
   * If the upgrade completes successfully, the upgradeState should be set back to NONE.
   * If the upgrade fails, then the user can retry to set it back into PENDING or IN_PROGRESS.
   * If the upgrade is aborted, then the upgradeState should be set back to NONE.
   * @param upgradeState
   */
  @Override
  public void setUpgradeState(UpgradeState upgradeState) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        stateEntity.setUpgradeState(upgradeState);
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  @Transactional
  public void handleEvent(ServiceComponentHostEvent event)
      throws InvalidStateTransitionException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Handling ServiceComponentHostEvent event,"
          + " eventType=" + event.getType().name()
          + ", event=" + event.toString());
    }
    State oldState = getState();
    clusterGlobalLock.readLock().lock();
    try {
      try {
        writeLock.lock();
        try {
          stateMachine.doTransition(event.getType(), event);
          stateEntity.setCurrentState(stateMachine.getCurrentState());
          saveIfPersisted();
          // TODO Audit logs
        } catch (InvalidStateTransitionException e) {
          LOG.debug("Can't handle ServiceComponentHostEvent event at"
              + " current state"
              + ", serviceComponentName=" + getServiceComponentName()
              + ", hostName=" + getHostName()
              + ", currentState=" + oldState
              + ", eventType=" + event.getType()
              + ", event=" + event);
          throw e;
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

    if (!oldState.equals(getState())) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("ServiceComponentHost transitioned to a new state"
            + ", serviceComponentName=" + getServiceComponentName()
            + ", hostName=" + getHostName()
            + ", oldState=" + oldState
            + ", currentState=" + getState()
            + ", eventType=" + event.getType().name()
            + ", event=" + event);
      }
    }
  }

  @Override
  public String getServiceComponentName() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return serviceComponent.getName();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public String getHostName() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return host.getHostName();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  /**
   * @return the lastOpStartTime
   */
  public long getLastOpStartTime() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return lastOpStartTime;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  /**
   * @param lastOpStartTime the lastOpStartTime to set
   */
  public void setLastOpStartTime(long lastOpStartTime) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        this.lastOpStartTime = lastOpStartTime;
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  /**
   * @return the lastOpEndTime
   */
  public long getLastOpEndTime() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return lastOpEndTime;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  /**
   * @param lastOpEndTime the lastOpEndTime to set
   */
  public void setLastOpEndTime(long lastOpEndTime) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        this.lastOpEndTime = lastOpEndTime;
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  /**
   * @return the lastOpLastUpdateTime
   */
  public long getLastOpLastUpdateTime() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return lastOpLastUpdateTime;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  /**
   * @param lastOpLastUpdateTime the lastOpLastUpdateTime to set
   */
  public void setLastOpLastUpdateTime(long lastOpLastUpdateTime) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        this.lastOpLastUpdateTime = lastOpLastUpdateTime;
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public long getClusterId() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return serviceComponent.getClusterId();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public String getServiceName() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return serviceComponent.getServiceName();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public StackId getStackVersion() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return gson.fromJson(stateEntity.getCurrentStackVersion(), StackId.class);
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setStackVersion(StackId stackVersion) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        stateEntity.setCurrentStackVersion(gson.toJson(stackVersion));
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public State getDesiredState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return desiredStateEntity.getDesiredState();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setDesiredState(State state) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        desiredStateEntity.setDesiredState(state);
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public StackId getDesiredStackVersion() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return gson.fromJson(desiredStateEntity.getDesiredStackVersion(), StackId.class);
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setDesiredStackVersion(StackId stackVersion) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        desiredStateEntity.setDesiredStackVersion(gson.toJson(stackVersion));
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public HostComponentAdminState getComponentAdminState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        HostComponentAdminState adminState = desiredStateEntity.getAdminState();
        if (adminState == null
            && !serviceComponent.isClientComponent() && !serviceComponent.isMasterComponent()) {
          adminState = HostComponentAdminState.INSERVICE;
        }
        return adminState;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setComponentAdminState(HostComponentAdminState attribute) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        desiredStateEntity.setAdminState(attribute);
        saveIfPersisted();
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public ServiceComponentHostResponse convertToResponse() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        ServiceComponentHostResponse r = new ServiceComponentHostResponse(
            serviceComponent.getClusterName(),
            serviceComponent.getServiceName(),
            serviceComponent.getName(),
            getHostName(),
            getState().toString(),
            getStackVersion().getStackId(),
            getDesiredState().toString(),
            getDesiredStackVersion().getStackId(),
            getComponentAdminState());

        r.setActualConfigs(actualConfigs);

        try {
          r.setStaleConfig(helper.isStaleConfigs(this));
        } catch (Exception e) {
          LOG.error("Could not determine stale config", e);
        }

        return r;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public String getClusterName() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return serviceComponent.getClusterName();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void debugDump(StringBuilder sb) {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        sb.append("ServiceComponentHost={ hostname=").append(getHostName())
          .append(", serviceComponentName=").append(serviceComponent.getName())
          .append(", clusterName=").append(serviceComponent.getClusterName())
          .append(", serviceName=").append(serviceComponent.getServiceName())
          .append(", desiredStackVersion=").append(getDesiredStackVersion())
          .append(", desiredState=").append(getDesiredState())
          .append(", stackVersion=").append(getStackVersion())
          .append(", state=").append(getState())
          .append(", securityState=").append(getSecurityState())
          .append(", desiredSecurityState=").append(getDesiredSecurityState())
          .append(" }");
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public boolean isPersisted() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return persisted;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void persist() {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        if (!persisted) {
          persistEntities();
          refresh();
          host.refresh();
          serviceComponent.refresh();
          persisted = true;

          // publish the service component installed event
          StackId stackId = getDesiredStackVersion();

          ServiceComponentInstalledEvent event = new ServiceComponentInstalledEvent(
              getClusterId(), stackId.getStackName(),
              stackId.getStackVersion(), getServiceName(), getServiceComponentName(), getHostName());

          eventPublisher.publish(event);
        } else {
          saveIfPersisted();
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Transactional
  protected void persistEntities() {
    HostEntity hostEntity = hostDAO.findByName(getHostName());
    hostEntity.getHostComponentStateEntities().add(stateEntity);
    hostEntity.getHostComponentDesiredStateEntities().add(desiredStateEntity);

    ServiceComponentDesiredStateEntityPK dpk = new ServiceComponentDesiredStateEntityPK();
    dpk.setClusterId(serviceComponent.getClusterId());
    dpk.setServiceName(serviceComponent.getServiceName());
    dpk.setComponentName(serviceComponent.getName());

    ServiceComponentDesiredStateEntity serviceComponentDesiredStateEntity = serviceComponentDesiredStateDAO.findByPK(dpk);
    serviceComponentDesiredStateEntity.getHostComponentDesiredStateEntities().add(desiredStateEntity);

    desiredStateEntity.setServiceComponentDesiredStateEntity(serviceComponentDesiredStateEntity);
    desiredStateEntity.setHostEntity(hostEntity);
    stateEntity.setServiceComponentDesiredStateEntity(serviceComponentDesiredStateEntity);
    stateEntity.setHostEntity(hostEntity);

    hostComponentStateDAO.create(stateEntity);
    hostComponentDesiredStateDAO.create(desiredStateEntity);

    serviceComponentDesiredStateDAO.merge(serviceComponentDesiredStateEntity);
    hostDAO.merge(hostEntity);
  }

  @Override
  @Transactional
  public void refresh() {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        if (isPersisted()) {
          HostComponentStateEntityPK pk = new HostComponentStateEntityPK();
          HostComponentDesiredStateEntityPK dpk = new HostComponentDesiredStateEntityPK();
          pk.setClusterId(getClusterId());
          pk.setComponentName(getServiceComponentName());
          pk.setServiceName(getServiceName());
          pk.setHostName(getHostName());
          dpk.setClusterId(getClusterId());
          dpk.setComponentName(getServiceComponentName());
          dpk.setServiceName(getServiceName());
          dpk.setHostName(getHostName());
          stateEntity = hostComponentStateDAO.findByPK(pk);
          desiredStateEntity = hostComponentDesiredStateDAO.findByPK(dpk);
          hostComponentStateDAO.refresh(stateEntity);
          hostComponentDesiredStateDAO.refresh(desiredStateEntity);
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Transactional
  private void saveIfPersisted() {
    if (isPersisted()) {
      hostComponentStateDAO.merge(stateEntity);
      hostComponentDesiredStateDAO.merge(desiredStateEntity);
    }
  }

  @Override
  public boolean canBeRemoved() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {

        return (getState().isRemovableState());

      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public void delete() {
    boolean fireRemovalEvent = false;

    clusterGlobalLock.writeLock().lock();
    try {
      writeLock.lock();
      try {
        if (persisted) {
          removeEntities();
          persisted = false;
          fireRemovalEvent = true;
        }

        clusters.getCluster(getClusterName()).removeServiceComponentHost(this);
      } catch (AmbariException ex) {
        LOG.error("Unable to remove a service component from a host", ex);
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.writeLock().unlock();
    }

    // publish event for the removal of the SCH after the removal is
    // completed, but only if it was persisted
    if (fireRemovalEvent) {
      long clusterId = getClusterId();
      StackId stackId = getStackVersion();
      String stackVersion = stackId.getStackVersion();
      String stackName = stackId.getStackName();
      String serviceName = getServiceName();
      String componentName = getServiceComponentName();
      String hostName = getHostName();

      ServiceComponentUninstalledEvent event = new ServiceComponentUninstalledEvent(
          clusterId, stackName, stackVersion, serviceName, componentName,
          hostName);

      eventPublisher.publish(event);
    }
  }

  @Transactional
  protected void removeEntities() {
    HostComponentStateEntityPK pk = new HostComponentStateEntityPK();
    pk.setClusterId(stateEntity.getClusterId());
    pk.setComponentName(stateEntity.getComponentName());
    pk.setServiceName(stateEntity.getServiceName());
    pk.setHostName(stateEntity.getHostName());

    hostComponentStateDAO.removeByPK(pk);

    HostComponentDesiredStateEntityPK desiredPK = new HostComponentDesiredStateEntityPK();
    desiredPK.setClusterId(desiredStateEntity.getClusterId());
    desiredPK.setComponentName(desiredStateEntity.getComponentName());
    desiredPK.setServiceName(desiredStateEntity.getServiceName());
    desiredPK.setHostName(desiredStateEntity.getHostName());

    hostComponentDesiredStateDAO.removeByPK(desiredPK);
  }

  @Override
  public void updateActualConfigs(Map<String, Map<String, String>> configTags) {
    Map<Long, ConfigGroup> configGroupMap;
    String clusterName = getClusterName();
    try {
      Cluster cluster = clusters.getCluster(clusterName);
      configGroupMap = cluster.getConfigGroups();
    } catch (AmbariException e) {
      LOG.warn("Unable to find cluster, " + clusterName);
      return;
    }

    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        LOG.debug("Updating actual config tags: " + configTags);
        actualConfigs = new HashMap<String, HostConfig>();

        for (Entry<String, Map<String, String>> entry : configTags.entrySet()) {
          String type = entry.getKey();
          Map<String, String> values = new HashMap<String, String>(entry.getValue());

          String tag = values.get(ConfigHelper.CLUSTER_DEFAULT_TAG);
          values.remove(ConfigHelper.CLUSTER_DEFAULT_TAG);

          HostConfig hc = new HostConfig();
          hc.setDefaultVersionTag(tag);
          actualConfigs.put(type, hc);

          if (!values.isEmpty()) {
            for (Entry<String, String> overrideEntry : values.entrySet()) {
              Long groupId = Long.parseLong(overrideEntry.getKey());
              hc.getConfigGroupOverrides().put(groupId, overrideEntry.getValue());
              if (!configGroupMap.containsKey(groupId)) {
                LOG.debug("Config group does not exist, id = " + groupId);
              }
            }
          }
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }



  @Override
  public Map<String, HostConfig> getActualConfigs() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return actualConfigs;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }

  }

  @Override
  public HostState getHostState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return host.getState();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setMaintenanceState(MaintenanceState state) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        desiredStateEntity.setMaintenanceState(state);
        saveIfPersisted();

        // broadcast the maintenance mode change
        MaintenanceModeEvent event = new MaintenanceModeEvent(state, this);
        eventPublisher.publish(event);

      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public MaintenanceState getMaintenanceState() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return desiredStateEntity.getMaintenanceState();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setProcesses(List<Map<String, String>> procs) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        processes = Collections.unmodifiableList(procs);
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public List<Map<String, String>> getProcesses() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return processes;
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public boolean isRestartRequired() {
    clusterGlobalLock.readLock().lock();
    try {
      readLock.lock();
      try {
        return desiredStateEntity.isRestartRequired();
      } finally {
        readLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void setRestartRequired(boolean restartRequired) {
    clusterGlobalLock.readLock().lock();
    try {
      writeLock.lock();
      try {
        desiredStateEntity.setRestartRequired(restartRequired);
        saveIfPersisted();
        helper.invalidateStaleConfigsCache(this);
      } finally {
        writeLock.unlock();
      }
    } finally {
      clusterGlobalLock.readLock().unlock();
    }
  }

  @Override
  public void recalculateHostVersionState() throws AmbariException {
    final String version = getVersion();
    final String hostName = getHostName();
    final HostEntity host = hostDAO.findByName(hostName);
    final Set<Cluster> clustersForHost = clusters.getClustersForHost(hostName);
    if (clustersForHost.size() != 1) {
      throw new AmbariException("Host " + hostName + " should be assigned only to one cluster");
    }
    final Cluster cluster = clustersForHost.iterator().next();
    final StackId stack = cluster.getDesiredStackVersion();
    final RepositoryVersionEntity repositoryVersion = repositoryVersionDAO.findByStackAndVersion(stack.getStackId(), version);
    if (repositoryVersion == null) {
      LOG.debug("Repository version for stack " + stack.getStackId() + " for version " + version + " was not found");
      return;
    }
    final HostVersionEntity hostVersionEntity = hostVersionDAO.findByClusterStackVersionAndHost(cluster.getClusterName(), repositoryVersion.getStack(), repositoryVersion.getVersion(), hostName);
    if (hostVersionEntity == null) {
      LOG.debug(String.format("Host version version for host %s on cluster %s with stack %s and repository version %s was not found",
          hostName, cluster.getClusterName(), repositoryVersion.getStack(), repositoryVersion.getVersion()));
      return;
    }

    final Collection<HostComponentStateEntity> allHostComponents = host.getHostComponentStateEntities();
    final Collection<HostComponentStateEntity> upgradedHostComponents = new HashSet<HostComponentStateEntity>();
    for (HostComponentStateEntity hostComponentStateEntity: allHostComponents) {
      if (hostComponentStateEntity.getUpgradeState().equals(UpgradeState.COMPLETE) && !hostComponentStateEntity.getVersion().equals("UNKNOWN")) {
        upgradedHostComponents.add(hostComponentStateEntity);
      }
    }

    //TODO hack: clients' states are not updated, probably we should check the state of master components
    final Collection<HostComponentStateEntity> nonUpgradedHostComponents = CollectionUtils.subtract(allHostComponents, upgradedHostComponents);
    for (HostComponentStateEntity hostComponentStateEntity: nonUpgradedHostComponents) {
      final Service service = cluster.getService(hostComponentStateEntity.getServiceName());
      if (service.getServiceComponent(hostComponentStateEntity.getComponentName()).isClientComponent()) {
        upgradedHostComponents.add(hostComponentStateEntity);
      }
    }

    if (allHostComponents.size() == upgradedHostComponents.size() &&
        (hostVersionEntity.getState().equals(RepositoryVersionState.INSTALLED) || hostVersionEntity.getState().equals(RepositoryVersionState.UPGRADING))) {
      hostVersionEntity.setState(RepositoryVersionState.UPGRADED);
      hostVersionDAO.merge(hostVersionEntity);
    }

    if (!upgradedHostComponents.isEmpty() && upgradedHostComponents.size() < allHostComponents.size()) {
      hostVersionEntity.setState(RepositoryVersionState.UPGRADING);
      hostVersionDAO.merge(hostVersionEntity);
    }

    cluster.recalculateClusterVersionState(version);
  }
}
