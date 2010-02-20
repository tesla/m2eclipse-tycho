/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.sonatype.tycho.m2e.internal;

import java.io.File;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.internal.core.ClasspathComputer;
import org.eclipse.pde.internal.core.natures.PDE;
import org.maven.ide.eclipse.project.IMavenProjectFacade;
import org.maven.ide.eclipse.project.configurator.ILifecycleMapping;
import org.maven.ide.eclipse.tests.common.AbstractLifecycleMappingTest;

@SuppressWarnings( "restriction" )
public class TychoLifecycleMappingTest
    extends AbstractLifecycleMappingTest
{
    private IMavenProjectFacade importProjectAndAssertLifecycleMappingType( String basedir, String pomName )
        throws Exception
    {
        assertTrue( new File( basedir, pomName ).exists() );
        
        IMavenProjectFacade facade = importMavenProject( basedir, pomName );
        assertNotNull( facade );
        ILifecycleMapping lifecycleMapping = projectConfigurationManager.getLifecycleMapping( facade, monitor );

        assertNotNull( lifecycleMapping );
        assertTrue( lifecycleMapping instanceof TychoLifecycleMapping );
        
        return facade;
    }
    
    public void testTychoLifecycleMapping_EclipsePlugin()
        throws Exception
    {
        IMavenProjectFacade facade = importProjectAndAssertLifecycleMappingType( "projects/lifecyclemapping", "tycho-eclipse-plugin/pom.xml" );

        IProject project = facade.getProject();
        assertTrue( project.hasNature( PDE.PLUGIN_NATURE ) );
        assertTrue( project.hasNature( JavaCore.NATURE_ID ) );
        IPluginModelBase model = PluginRegistry.findModel( project );
        assertNotNull( model );

        IClasspathEntry[] classpathEntries =
            ClasspathComputer.getClasspath( project, model, null /* sourceLibraryMap */, false /* clear */, true /* overrideCompliance */);
        assertEquals( 2, classpathEntries.length );
        assertClasspathContains( classpathEntries, "org.eclipse.jdt.launching.JRE_CONTAINER" );
        assertClasspathContains( classpathEntries, "org.eclipse.pde.core.requiredPlugins" );
    }

    private void assertClasspathContains( IClasspathEntry[] classpathEntries, String path )
    {
        for ( IClasspathEntry classpathEntry : classpathEntries )
        {
            if ( path.equals( classpathEntry.getPath().toString() ) )
            {
                return;
            }
        }
        fail( "Classpath does not contain: " + path );
    }

    public void testTychoLifecycleMapping_EclipseTestPlugin()
        throws Exception
    {
        IMavenProjectFacade facade = importProjectAndAssertLifecycleMappingType( "projects/lifecyclemapping", "tycho-eclipse-test-plugin/pom.xml" );

        IProject project = facade.getProject();
        assertTrue( project.hasNature( PDE.PLUGIN_NATURE ) );
        assertTrue( project.hasNature( JavaCore.NATURE_ID ) );
        IPluginModelBase model = PluginRegistry.findModel( project );
        assertNotNull( model );

        IClasspathEntry[] classpathEntries =
            ClasspathComputer.getClasspath( project, model, null /* sourceLibraryMap */, false /* clear */, true /* overrideCompliance */);
        assertEquals( 2, classpathEntries.length );
        assertClasspathContains( classpathEntries, "org.eclipse.jdt.launching.JRE_CONTAINER" );
        assertClasspathContains( classpathEntries, "org.eclipse.pde.core.requiredPlugins" );
    }

    public void testTychoLifecycleMapping_EclipseFeature()
        throws Exception
    {
        IMavenProjectFacade facade = importProjectAndAssertLifecycleMappingType( "projects/lifecyclemapping", "tycho-eclipse-feature/pom.xml" );

        IProject project = facade.getProject();
        assertTrue( project.hasNature( PDE.FEATURE_NATURE ) );
    }

    public void testTychoLifecycleMapping_EclipseUpdateSite()
        throws Exception
    {
        IMavenProjectFacade facade = importProjectAndAssertLifecycleMappingType( "projects/lifecyclemapping", "tycho-eclipse-update-site/pom.xml" );

        IProject project = facade.getProject();
        assertTrue( project.hasNature( PDE.SITE_NATURE ) );
    }
}
