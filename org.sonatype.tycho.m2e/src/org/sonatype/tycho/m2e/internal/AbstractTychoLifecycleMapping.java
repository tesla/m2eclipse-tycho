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
import org.apache.maven.project.MavenProject;
import org.eclipse.core.resources.IProject;
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
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;
import org.eclipse.pde.internal.core.natures.PDE;
import org.eclipse.pde.internal.core.util.CoreUtility;

@SuppressWarnings( "restriction" )
public abstract class AbstractTychoLifecycleMapping
    extends AbstractCustomizableLifecycleMapping
    implements ILifecycleMapping, ILifecycleMapping2
{

    private PDEProjectHelper pdeHelper = PDEProjectHelper.getInstance();

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
    public void configure( ProjectConfigurationRequest request, IProgressMonitor monitor )
        throws CoreException
    {
        super.configure( request, monitor );

        MavenProject mavenProject = request.getMavenProject();
        IProject project = request.getProject();

        String packaging = mavenProject.getPackaging();
        if ( "eclipse-plugin".equals( packaging ) || "eclipse-test-plugin".equals( packaging ) )
        {
            pdeHelper.configurePDEBundleProject( project, mavenProject, monitor );
        }
        else if ( "eclipse-feature".equals( packaging ) )
        {
            // see org.eclipse.pde.internal.ui.wizards.feature.AbstractCreateFeatureOperation
            if ( !project.hasNature( PDE.FEATURE_NATURE ) )
            {
                CoreUtility.addNatureToProject( project, PDE.FEATURE_NATURE, monitor );
            }
        }
        else if ( "eclipse-update-site".equals( packaging ) )
        {
            // see org.eclipse.pde.internal.ui.wizards.site.NewSiteProjectCreationOperation
            if ( !project.hasNature( PDE.SITE_NATURE ) )
            {
                CoreUtility.addNatureToProject( project, PDE.SITE_NATURE, monitor );
            }
        }
    }

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
            if ( configurator instanceof OsgiBundleProjectConfigurator )
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
