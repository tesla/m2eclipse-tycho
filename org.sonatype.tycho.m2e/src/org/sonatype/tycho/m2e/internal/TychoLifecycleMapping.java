/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.sonatype.tycho.m2e.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.apache.maven.execution.MavenExecutionRequest;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.m2e.core.internal.project.registry.AbstractMavenDependencyResolver;
import org.eclipse.m2e.core.internal.project.registry.Capability;
import org.eclipse.m2e.core.internal.project.registry.ILifecycleMapping2;
import org.eclipse.m2e.core.internal.project.registry.RequiredCapability;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.configurator.AbstractCustomizableLifecycleMapping;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.ILifecycleMapping;
import org.sonatype.tycho.m2e.felix.internal.MavenBundlePluginConfigurator;

@SuppressWarnings( "restriction" )
public class TychoLifecycleMapping
    extends AbstractCustomizableLifecycleMapping
    implements ILifecycleMapping, ILifecycleMapping2
{

    private static final AbstractMavenDependencyResolver NOOP_DEPENDENCY_RESOLVER =
        new AbstractMavenDependencyResolver()
        {
            @Override
            public void resolveProjectDependencies( IMavenProjectFacade facade, MavenExecutionRequest mavenRequest,
                                                    Set<Capability> capabilities, Set<RequiredCapability> requirements,
                                                    IProgressMonitor monitor )
                throws CoreException
            {
            }
        };

    @Override
    public List<AbstractProjectConfigurator> getProjectConfigurators( IMavenProjectFacade projectFacade,
                                                                      IProgressMonitor monitor )
    {
        List<AbstractProjectConfigurator> configurators =
            new ArrayList<AbstractProjectConfigurator>( super.getProjectConfigurators( projectFacade, monitor ) );

        // workaround for tycho projects that have maven-bundle-plugin enabled for them
        ListIterator<AbstractProjectConfigurator> li = configurators.listIterator();
        while ( li.hasNext() )
        {
            AbstractProjectConfigurator configurator = li.next();
            if ( configurator instanceof MavenBundlePluginConfigurator
                || configurator instanceof PDEMavenBundlePluginConfigurator )
            {
                li.remove();
            }
        }

        return configurators;
    }

    public AbstractMavenDependencyResolver getDependencyResolver( IProgressMonitor monitor )
    {
        return NOOP_DEPENDENCY_RESOLVER;
    }
}
