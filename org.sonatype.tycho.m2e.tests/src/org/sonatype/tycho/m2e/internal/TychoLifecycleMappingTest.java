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
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.configurator.ILifecycleMapping;
import org.eclipse.m2e.tests.common.AbstractLifecycleMappingTest;
import org.eclipse.m2e.tests.common.WorkspaceHelpers;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.internal.core.ClasspathComputer;
import org.eclipse.pde.internal.core.natures.PDE;

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
        WorkspaceHelpers.assertNoErrors( facade.getProject() );
        ILifecycleMapping lifecycleMapping = projectConfigurationManager.getLifecycleMapping( facade );

        assertNotNull( "Expected not null lifecycleMapping", lifecycleMapping );
        assertTrue( lifecycleMapping.getClass().getName(), lifecycleMapping instanceof TychoLifecycleMapping );

        return facade;
    }

    public void testTychoLifecycleMapping_EclipsePlugin()
        throws Exception
    {
        IMavenProjectFacade facade =
            importProjectAndAssertLifecycleMappingType( "projects/lifecyclemapping", "tycho-eclipse-plugin/pom.xml" );

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
        IMavenProjectFacade facade =
            importProjectAndAssertLifecycleMappingType( "projects/lifecyclemapping",
                                                        "tycho-eclipse-test-plugin/pom.xml" );

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
        IMavenProjectFacade facade =
            importProjectAndAssertLifecycleMappingType( "projects/lifecyclemapping", "tycho-eclipse-feature/pom.xml" );

        IProject project = facade.getProject();
        assertTrue( project.hasNature( PDE.FEATURE_NATURE ) );
    }

    public void testTychoLifecycleMapping_EclipseUpdateSite()
        throws Exception
    {
        IMavenProjectFacade facade =
            importProjectAndAssertLifecycleMappingType( "projects/lifecyclemapping",
                                                        "tycho-eclipse-update-site/pom.xml" );

        IProject project = facade.getProject();
        assertTrue( project.hasNature( PDE.SITE_NATURE ) );
    }

    public void testUpdateWhenJarInClasspath_EclipsePlugin()
        throws Exception
    {
        IMavenProjectFacade facade =
            importProjectAndAssertLifecycleMappingType( "projects/local-jar-classpath", "pom.xml" );

        IProject project = facade.getProject();
        assertTrue( project.hasNature( PDE.PLUGIN_NATURE ) );
        assertTrue( project.hasNature( JavaCore.NATURE_ID ) );
        IPluginModelBase model = PluginRegistry.findModel( project );
        assertNotNull( model );

        IJavaProject jproject = JavaCore.create( project );
        assertNotNull( jproject );
        IClasspathEntry[] classpathEntries = jproject.getRawClasspath();
        assertEquals( 4, classpathEntries.length );
        MavenPlugin.getProjectConfigurationManager().updateProjectConfiguration( project, monitor );
        jproject = null;
        waitForJobsToComplete();

        jproject = JavaCore.create( project );
        classpathEntries = jproject.getRawClasspath();
        assertEquals( 4, classpathEntries.length );
        assertClasspathContains( classpathEntries, "org.eclipse.jdt.launching.JRE_CONTAINER" );
        assertClasspathContains( classpathEntries, "org.eclipse.pde.core.requiredPlugins" );
        assertClasspathContains( classpathEntries, "/local-jar-classpath/src/main/java/" );
        assertClasspathContains( classpathEntries, "/local-jar-classpath/lib/local-jar-classpath.jar" );

    }
}
