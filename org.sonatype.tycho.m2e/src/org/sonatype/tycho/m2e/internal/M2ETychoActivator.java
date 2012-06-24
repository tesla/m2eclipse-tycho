/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.sonatype.tycho.m2e.internal;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.pde.core.project.IBundleProjectService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

@SuppressWarnings( { "rawtypes", "unchecked" } )
public class M2ETychoActivator
    extends Plugin
{

    public static final String PLUGIN_ID = "org.sonatype.tycho.m2e";

    private static M2ETychoActivator plugin;

    private ServiceReference projectServiceRef;

    private IBundleProjectService projectService;

    public M2ETychoActivator()
    {
    }

    public void start( BundleContext context )
        throws Exception
    {
        super.start( context );
        plugin = this;

        projectServiceRef = context.getServiceReference( IBundleProjectService.class.getName() );

        projectService = (IBundleProjectService) context.getService( projectServiceRef );

    }

    public void stop( BundleContext context )
        throws Exception
    {
        context.ungetService( projectServiceRef );
        projectService = null;
        projectServiceRef = null;

        plugin = null;
        super.stop( context );
    }

    public static M2ETychoActivator getDefault()
    {
        return plugin;
    }

    public IBundleProjectService getProjectService()
    {
        return projectService;
    }

}
