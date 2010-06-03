/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.sonatype.tycho.m2e.internal;

import org.eclipse.pde.core.project.IBundleProjectService;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

public class Activator
    implements BundleActivator
{

    // The plug-in ID
    public static final String PLUGIN_ID = "org.sonatype.tycho.m2e";

    // The shared instance
    private static Activator plugin;

    private ServiceReference projectServiceRef;

    private IBundleProjectService projectService;

    public Activator()
    {
    }

    public void start( BundleContext context )
        throws Exception
    {
        plugin = this;

        projectServiceRef = context.getServiceReference( IBundleProjectService.class.getName() );

        projectService = (IBundleProjectService) context.getService( projectServiceRef );
    }

    public void stop( BundleContext context )
        throws Exception
    {
        context.ungetService( projectServiceRef );

        plugin = null;
    }

    public static Activator getDefault()
    {
        return plugin;
    }

    public IBundleProjectService getProjectService()
    {
        return projectService;
    }
}
