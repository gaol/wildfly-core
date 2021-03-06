/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import org.jboss.as.controller.access.Action;
import org.jboss.as.controller.access.AuthorizationResult;
import org.jboss.as.controller.access.ResourceAuthorization;
import org.jboss.as.controller.audit.AuditLogger;
import org.jboss.as.controller.client.MessageSeverity;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.notification.Notification;
import org.jboss.as.controller.persistence.ConfigurationPersistenceException;
import org.jboss.as.controller.persistence.ConfigurationPersister;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.service.ServiceTarget;

/**
 * {@link OperationContext} implementation for parallel handling of subsystem operations during boot.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
@SuppressWarnings("deprecation")
class ParallelBootOperationContext extends AbstractOperationContext {

    private final AbstractOperationContext primaryContext;
    private final List<ParsedBootOp> runtimeOps;

    private Step lockStep;
    private final int operationId;
    private final ModelControllerImpl controller;

    ParallelBootOperationContext(final ModelController.OperationTransactionControl transactionControl,
                                 final ControlledProcessState processState, final AbstractOperationContext primaryContext,
                                 final List<ParsedBootOp> runtimeOps, final Thread controllingThread,
                                 final ModelControllerImpl controller, final int operationId, final AuditLogger auditLogger, final Resource model) {
        super(primaryContext.getProcessType(), primaryContext.getRunningMode(), transactionControl, processState, true, auditLogger, controller.getNotificationSupport());
        this.primaryContext = primaryContext;
        this.runtimeOps = runtimeOps;
        AbstractOperationContext.controllingThread.set(controllingThread);
        //
        this.controller = controller;
        this.operationId = operationId;
    }

    void close() {
        AbstractOperationContext.controllingThread.remove();
    }

    @Override
    public void addStep(OperationStepHandler step, Stage stage) throws IllegalArgumentException {
        if (activeStep == null) {
            throw ControllerLogger.ROOT_LOGGER.noActiveStep();
        }
        addStep(activeStep.response, activeStep.operation, step, stage);
    }

    @Override
    public void addStep(ModelNode operation, OperationStepHandler step, Stage stage) throws IllegalArgumentException {
        if (activeStep == null) {
            throw ControllerLogger.ROOT_LOGGER.noActiveStep();
        }
        addStep(activeStep.response, operation, step, stage);
    }

    @Override
    public void addStep(ModelNode operation, OperationStepHandler step, Stage stage, final boolean addFirst) throws IllegalArgumentException {
        if (activeStep == null) {
            throw ControllerLogger.ROOT_LOGGER.noActiveStep();
        }
        addStep(activeStep.response, operation, step, stage, addFirst);
    }

    @Override
    public void addStep(ModelNode response, ModelNode operation, OperationStepHandler step, Stage stage) throws IllegalArgumentException {
        switch (stage) {
            case MODEL:
                super.addStep(response, operation, step, stage);
                break;
            case RUNTIME:
                if (runtimeOps != null) {
                    // Cache for use by the runtime step from ParallelBootOperationStepHandler
                    ParsedBootOp parsedOp = new ParsedBootOp(operation, step, response);
                    runtimeOps.add(parsedOp);
                } else {
                    super.addStep(response, operation, step, stage);
                }
                break;
            default:
                // Handle VERIFY in the primary context, after parallel work is done
                primaryContext.addStep(response, operation, step, stage);
        }
    }

    // Methods unimplemented by superclass

    @Override
    public InputStream getAttachmentStream(int index) {
        return primaryContext.getAttachmentStream(index);
    }

    @Override
    public int getAttachmentStreamCount() {
        return primaryContext.getAttachmentStreamCount();
    }

    @Override
    public boolean isRollbackOnRuntimeFailure() {
        return primaryContext.isRollbackOnRuntimeFailure();
    }

    @Override
    public boolean isResourceServiceRestartAllowed() {
        return primaryContext.isResourceServiceRestartAllowed();
    }

    @Override
    public ImmutableManagementResourceRegistration getResourceRegistration() {
        ImmutableManagementResourceRegistration parent = primaryContext.getResourceRegistration();
        return  parent.getSubModel(activeStep.address);
    }

    @Override
    public ManagementResourceRegistration getResourceRegistrationForUpdate() {
        acquireControllerLock();
        ManagementResourceRegistration parent = primaryContext.getResourceRegistrationForUpdate();
        return  parent.getSubModel(activeStep.address);
    }

    @Override
    public ImmutableManagementResourceRegistration getRootResourceRegistration() {
        return primaryContext.getRootResourceRegistration();
    }

    @Override
    public ServiceRegistry getServiceRegistry(boolean modify) throws UnsupportedOperationException {
        if(modify) {
            acquireControllerLock();
        }
        return primaryContext.getServiceRegistry(modify);
    }

    @Override
    public ServiceController<?> removeService(ServiceName name) throws UnsupportedOperationException {
        acquireControllerLock();
        return primaryContext.removeService(name);
    }

    @Override
    public void removeService(ServiceController<?> controller) throws UnsupportedOperationException {
        acquireControllerLock();
        primaryContext.removeService(controller);
    }

    @Override
    public ServiceTarget getServiceTarget() throws UnsupportedOperationException {
        acquireControllerLock();
        return primaryContext.getServiceTarget();
    }

    @Override
    public void acquireControllerLock() {
        if(lockStep == null) {
            try {
                controller.acquireLock(operationId, true);
                lockStep = activeStep;
            } catch (InterruptedException e) {
                cancelled = true;
                Thread.currentThread().interrupt();
                throw ControllerLogger.ROOT_LOGGER.operationCancelledAsynchronously();
            }
        }
    }

    @Override
    public Resource createResource(PathAddress address) throws UnsupportedOperationException {
        acquireControllerLock();
        PathAddress fullAddress = activeStep.address.append(address);
        return primaryContext.createResource(fullAddress);
    }

    @Override
    public void addResource(PathAddress address, Resource toAdd) {
        acquireControllerLock();
        PathAddress fullAddress = activeStep.address.append(address);
        primaryContext.addResource(fullAddress, toAdd);
    }

    @Override
    public Resource readResource(PathAddress address) {
        return readResource(address, true);
    }

    @Override
    public Resource readResource(PathAddress address, boolean recursive) {
        PathAddress fullAddress = activeStep.address.append(address);
        return primaryContext.readResource(fullAddress, recursive);
    }

    @Override
    public Resource readResourceFromRoot(PathAddress address) {
        return readResourceFromRoot(address, true);
    }

    @Override
    public Resource readResourceFromRoot(PathAddress address, boolean recursive) {
        return primaryContext.readResourceFromRoot(address, recursive);
    }

    @Override
    public Resource readResourceForUpdate(PathAddress address) {
        acquireControllerLock();
        PathAddress fullAddress = activeStep.address.append(address);
        return primaryContext.readResourceForUpdate(fullAddress);
    }

    @Override
    public Resource removeResource(PathAddress address) throws UnsupportedOperationException {
        acquireControllerLock();
        PathAddress fullAddress = activeStep.address.append(address);
        return primaryContext.removeResource(fullAddress);
    }

    @Override
    public Resource getOriginalRootResource() {
        return primaryContext.getOriginalRootResource();
    }

    @Override
    public boolean isModelAffected() {
        return primaryContext.isModelAffected();
    }

    @Override
    public boolean isResourceRegistryAffected() {
        return primaryContext.isResourceRegistryAffected();
    }

    @Override
    public boolean isRuntimeAffected() {
        return primaryContext.isRuntimeAffected();
    }

    @Override
    public Stage getCurrentStage() {
        return primaryContext.getCurrentStage();
    }

    @Override
    public void report(MessageSeverity severity, String message) {
        primaryContext.report(severity, message);
    }

    @Override
    public boolean markResourceRestarted(PathAddress resource, Object owner) {
        throw new UnsupportedOperationException("Resource restarting is not supported during boot");
    }

    @Override
    public boolean revertResourceRestarted(PathAddress resource, Object owner) {
        throw new UnsupportedOperationException("Resource restarting is not supported during boot");
    }

    @Override
    void awaitServiceContainerStability() throws InterruptedException {
        // ignored
    }

    @Override
    ConfigurationPersister.PersistenceResource createPersistenceResource() throws ConfigurationPersistenceException {
        // We don't persist
        return null;
    }

    @Override
    public void emit(Notification notification) {
        primaryContext.emit(notification);
    }

    @Override
    void releaseStepLocks(Step step) {
        if(lockStep == step) {
            controller.releaseLock(operationId);
            lockStep = null;
        }
    }

    @Override
    void waitForRemovals() {
        // nothing to do
    }

    @Override
    boolean isReadOnly() {
        return primaryContext.isReadOnly();
    }

    @Override
    ManagementResourceRegistration getRootResourceRegistrationForUpdate() {
        return primaryContext.getRootResourceRegistrationForUpdate();
    }

    @Override
    public ModelNode resolveExpressions(ModelNode node) throws OperationFailedException {
        return primaryContext.resolveExpressions(node);
    }

    @Override
    public <T> T getAttachment(final AttachmentKey<T> key) {
        return primaryContext.getAttachment(key);
    }

    @Override
    public <T> T attach(final AttachmentKey<T> key, final T value) {
        return primaryContext.attach(key, value);
    }

    @Override
    public <T> T attachIfAbsent(final AttachmentKey<T> key, final T value) {
        return primaryContext.attachIfAbsent(key, value);
    }

    @Override
    public <T> T detach(final AttachmentKey<T> key) {
        return primaryContext.detach(key);
    }

    @Override
    public AuthorizationResult authorize(ModelNode operation) {
        return primaryContext.authorize(operation);
    }

    @Override
    public AuthorizationResult authorize(ModelNode operation, Set<Action.ActionEffect> effects) {
        return primaryContext.authorize(operation, effects);
    }

    @Override
    public AuthorizationResult authorize(ModelNode operation, String attribute, ModelNode currentValue) {
        return primaryContext.authorize(operation, attribute, currentValue);
    }

    @Override
    public AuthorizationResult authorize(ModelNode operation, String attribute, ModelNode currentValue, Set<Action.ActionEffect> effects) {
        return primaryContext.authorize(operation, attribute, currentValue, effects);
    }

    @Override
    public AuthorizationResult authorizeOperation(ModelNode operation) {
        return primaryContext.authorizeOperation(operation);
    }

    @Override
    public ResourceAuthorization authorizeResource(boolean attributes, boolean isDefaultResource) {
        return primaryContext.authorizeResource(attributes, isDefaultResource);
    }

    Resource getModel() {
        return primaryContext.getModel();
    }

    @Override
    void logAuditRecord() {
        // handled by the primary context
    }
}
