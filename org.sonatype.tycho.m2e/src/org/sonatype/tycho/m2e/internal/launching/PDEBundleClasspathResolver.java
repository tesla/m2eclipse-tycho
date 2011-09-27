/*******************************************************************************
 * Copyright (c) 2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.sonatype.tycho.m2e.internal.launching;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.jdt.IClasspathManager;
import org.eclipse.m2e.jdt.MavenJdtPlugin;
import org.eclipse.pde.core.IBundleClasspathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.tycho.m2e.internal.OsgiBundleProjectConfigurator;

public class PDEBundleClasspathResolver
    implements IBundleClasspathResolver
{
    private static final Logger log = LoggerFactory.getLogger( PDEBundleClasspathResolver.class );

    // no test dependencies in OSGi runtime
    private static final int CLASSPATH_SCOPE = IClasspathManager.CLASSPATH_RUNTIME;

    @Override
    public Map<IPath, Collection<IPath>> getAdditionalClasspathEntries( IJavaProject javaProject )
    {
        IProgressMonitor monitor = new NullProgressMonitor();

        Map<IPath, Collection<IPath>> result = new LinkedHashMap<IPath, Collection<IPath>>();

        IClasspathEntry[] cp = resolveMavenClasspath( javaProject, monitor );

        IProject project = javaProject.getProject();
        IWorkspaceRoot workspace = project.getWorkspace().getRoot();

        if ( cp == null )
        {
            return result;
        }

        for ( IClasspathEntry entry : cp )
        {
            String pathStr = getBundlePath( entry );
            if ( pathStr != null && !".".equals( pathStr ) ) // inlined dependencies are not supported at the moment
            {
                IPath path = new Path( pathStr );
                switch ( entry.getEntryKind() )
                {
                    case IClasspathEntry.CPE_PROJECT:
                        addClasspathMap( result, path, getBuildOutputLocation( workspace, entry.getPath().segment( 0 ) ) );
                        break;
                    case IClasspathEntry.CPE_LIBRARY:
                        addClasspathMap( result, path, entry.getPath() );
                        break;
                }
            }
        }

        return result;
    }

    private IPath getBuildOutputLocation( IWorkspaceRoot workspace, String projectName )
    {
        IProject project = workspace.getProject( projectName );

        IMavenProjectFacade facade = MavenPlugin.getMavenProjectRegistry().getProject( project );

        IResource outputFolder = workspace.findMember( facade.getOutputLocation() );

        return outputFolder.getLocation();
    }

    private void addClasspathMap( Map<IPath, Collection<IPath>> result, IPath bundlePath, IPath location )
    {
        Collection<IPath> locations = result.get( bundlePath );
        if ( locations == null )
        {
            locations = new LinkedHashSet<IPath>();
            result.put( bundlePath, locations );
        }
        locations.add( location );
    }

    @Override
    public Collection<IRuntimeClasspathEntry> getAdditionalSourceEntries( IJavaProject javaProject )
    {
        IProgressMonitor monitor = new NullProgressMonitor();

        Set<IRuntimeClasspathEntry> resolved = new LinkedHashSet<IRuntimeClasspathEntry>();

        IClasspathEntry[] cp = resolveMavenClasspath( javaProject, monitor );

        if ( cp == null )
        {
            return resolved;
        }

        for ( IClasspathEntry entry : cp )
        {
            if ( isBundleClasspathEntry( entry ) )
            {
                switch ( entry.getEntryKind() )
                {
                    case IClasspathEntry.CPE_PROJECT:
                        addProjectEntries( resolved, entry.getPath(), CLASSPATH_SCOPE, getArtifactClassifier( entry ),
                                           monitor );
                        break;
                    case IClasspathEntry.CPE_LIBRARY:
                        resolved.add( JavaRuntime.newArchiveRuntimeClasspathEntry( entry.getPath() ) );
                        break;
                }
            }
        }

        return resolved;
    }

    protected IClasspathEntry[] resolveMavenClasspath( IJavaProject javaProject, IProgressMonitor monitor )
    {

        IProject project = javaProject.getProject();
        try
        {
            MavenJdtPlugin plugin = MavenJdtPlugin.getDefault();
            IClasspathManager buildpathManager = plugin.getBuildpathManager();
            return buildpathManager.getClasspath( project, CLASSPATH_SCOPE, false, monitor );
        }
        catch ( CoreException e )
        {
            log.debug( "Could not resolve maven dependencies classpath container for project {}.", project.getName(), e );
        }

        return null;
    }

    private String getBundlePath( IClasspathEntry entry )
    {
        for ( IClasspathAttribute attr : entry.getExtraAttributes() )
        {
            if ( OsgiBundleProjectConfigurator.ATTR_BUNDLE_CLASSPATH.equals( attr.getName() ) )
            {
                return attr.getValue();
            }
        }
        return null;
    }

    private boolean isBundleClasspathEntry( IClasspathEntry entry )
    {
        return getBundlePath( entry ) != null;
    }

    protected void addProjectEntries( Set<IRuntimeClasspathEntry> resolved, IPath path, int scope, String classifier,
                                      final IProgressMonitor monitor )
    {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject( path.segment( 0 ) );
        IJavaProject javaProject = JavaCore.create( project );
        resolved.add( JavaRuntime.newProjectRuntimeClasspathEntry( javaProject ) );
    }

    private static String getArtifactClassifier( IClasspathEntry entry )
    {
        IClasspathAttribute[] attributes = entry.getExtraAttributes();
        for ( IClasspathAttribute attribute : attributes )
        {
            if ( IClasspathManager.CLASSIFIER_ATTRIBUTE.equals( attribute.getName() ) )
            {
                return attribute.getValue();
            }
        }
        return null;
    }

}
