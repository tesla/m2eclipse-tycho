/*******************************************************************************
 * Copyright (c) 2012 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.sonatype.tycho.m2e.insitu.internal;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.framework.BundleContext;

public class TychoInsituActivator
    extends Plugin
{
    private static TychoInsituActivator plugin;

    public static final String PLUGIN_ID = "org.sonatype.tycho.m2e.insitu";

    public static final String PREF_ENABLED = "m2e.tycho.insitu.enabled";

    @Override
    public void start( BundleContext context )
        throws Exception
    {
        super.start( context );
        plugin = this;
    }

    @Override
    public void stop( BundleContext context )
        throws Exception
    {
        plugin = null;
        super.stop( context );
    }

    public static TychoInsituActivator getDefault()
    {
        return plugin;
    }

    public static boolean isInstrumentationEnabled()
    {
        return Platform.getPreferencesService().getBoolean( PLUGIN_ID, PREF_ENABLED, false, null );
    }

    public static void setInstrumentationEnabled( boolean enabled )
    {
        InstanceScope.INSTANCE.getNode( PLUGIN_ID ).putBoolean( PREF_ENABLED, enabled );
    }
}
