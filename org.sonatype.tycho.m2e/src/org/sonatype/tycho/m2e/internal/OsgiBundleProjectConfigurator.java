/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.sonatype.tycho.m2e.internal;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.m2e.core.embedder.ArtifactKey;
import org.eclipse.m2e.core.lifecyclemapping.model.IPluginExecutionMetadata;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.eclipse.m2e.core.project.configurator.AbstractBuildParticipant;
import org.eclipse.m2e.core.project.configurator.ILifecycleMappingConfiguration;
import org.eclipse.m2e.core.project.configurator.MojoExecutionKey;
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;
import org.eclipse.m2e.jdt.IClasspathDescriptor;
import org.eclipse.m2e.jdt.IClasspathEntryDescriptor;
import org.eclipse.m2e.jdt.IClasspathManager;
import org.eclipse.m2e.jdt.IJavaProjectConfigurator;
import org.eclipse.osgi.util.ManifestElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OsgiBundleProjectConfigurator
    extends AbstractMavenBundlePluginProjectConfigurator
    implements IJavaProjectConfigurator
{
    private static final Logger log = LoggerFactory.getLogger( OsgiBundleProjectConfigurator.class );

    public static final String ATTR_BUNDLE_CLASSPATH = "Tycho-Bundle-ClassPath";

    private static final String EMBEDDED_ARTIFACTS = "Embedded-Artifacts";

    @Override
    public void configure( ProjectConfigurationRequest request, IProgressMonitor monitor )
        throws CoreException
    {
        if ( !isOsgiBundleProject( request.getMavenProjectFacade(), monitor ) )
        {
            throw new IllegalArgumentException();
        }

        // bundle manifest is generated in #configureRawClasspath, which is invoked earlier during project configuration

        IProject project = request.getProject();
        IMavenProjectFacade facade = request.getMavenProjectFacade();

        PDEProjectHelper.addPDENature( project, getMetainfPath( facade, monitor ), monitor );
    }

    private void generateBundleManifest( ProjectConfigurationRequest request, IProgressMonitor monitor )
        throws CoreException
    {
        List<MojoExecution> executions = getMojoExecutions( request, monitor );

        if ( executions.size() != 1 )
        {
            throw new IllegalArgumentException();
        }

        MojoExecution execution = amendMojoExecution( executions.get( 0 ) );

        maven.execute( request.getMavenProject(), execution, monitor );

        IPath manifestPath = getMetainfPath( request.getMavenProjectFacade(), monitor );

        request.getProject().getFolder( manifestPath ).refreshLocal( IResource.DEPTH_INFINITE, monitor );
    }

    @Override
    public AbstractBuildParticipant getBuildParticipant( IMavenProjectFacade projectFacade, MojoExecution execution,
                                                         IPluginExecutionMetadata executionMetadata )
    {
        return getBuildParticipant( execution );
    }

    @Override
    public void configureClasspath( IMavenProjectFacade facade, IClasspathDescriptor classpath, IProgressMonitor monitor )
        throws CoreException
    {
        Map<ArtifactKey, String> classpathMap = getBundleClasspathMap( facade );

        for ( IClasspathEntryDescriptor entry : classpath.getEntryDescriptors() )
        {
            String path = classpathMap.get( entry.getArtifactKey() );
            if ( path != null && !"".equals( path.trim() ) )
            {
                entry.setClasspathAttribute( ATTR_BUNDLE_CLASSPATH, path );
            }
        }
    }

    private Map<ArtifactKey, String> getBundleClasspathMap( IMavenProjectFacade facade )
        throws CoreException
    {
        Map<ArtifactKey, String> result = new LinkedHashMap<ArtifactKey, String>();

        IFile mfFile = PDEProjectHelper.getBundleManifest( facade.getProject() );

        if ( mfFile != null && mfFile.isAccessible() )
        {
            try
            {
                InputStream is = mfFile.getContents();

                try
                {
                    Manifest mf = new Manifest( is );

                    Attributes attrs = mf.getMainAttributes();
                    String value = attrs.getValue( EMBEDDED_ARTIFACTS );

                    if ( value != null )
                    {
                        for ( ManifestElement me : ManifestElement.parseHeader( EMBEDDED_ARTIFACTS, value ) )
                        {
                            String path = me.getValue();
                            String g = me.getAttribute( "g" );
                            String a = me.getAttribute( "a" );
                            String v = me.getAttribute( "v" );
                            String c = me.getAttribute( "c" );

                            if ( g != null && a != null && v != null && path != null )
                            {
                                result.put( new ArtifactKey( g, a, v, c ), path );
                            }
                            else
                            {
                                log.debug( "Malformd Include-Artifacts element paht={};g={};a={};v={};c={}",
                                           new Object[] { path, g, a, v, c } );
                            }
                        }
                    }

                }
                finally
                {
                    is.close();
                }
            }
            catch ( Exception e )
            {
                log.warn( "Count not read generated bundle manifest of project {}", facade.getProject().getName(), e );
            }
        }

        return result;
    }

    @Override
    public void configureRawClasspath( ProjectConfigurationRequest request, IClasspathDescriptor classpath,
                                       IProgressMonitor monitor )
        throws CoreException
    {
        // export maven dependencies classpath container, so dependent project can compile against embedded transitive
        // dependencies.
        // This breaks JDT classpath of plain maven dependents of this project, i.e. such dependents will be exposed to
        // more classes compared to CLI build. Not sure what to do about it yet.

        generateBundleManifest( request, monitor );

        if ( !getBundleClasspathMap( request.getMavenProjectFacade() ).isEmpty() )
        {
            for ( IClasspathEntryDescriptor entry : classpath.getEntryDescriptors() )
            {
                if ( IClasspathManager.CONTAINER_ID.equals( entry.getPath().segment( 0 ) ) )
                {
                    entry.setExported( true );
                }
            }
        }
    }

    @Override
    public void mavenProjectChanged( MavenProjectChangedEvent event, IProgressMonitor monitor )
        throws CoreException
    {
        if ( MavenProjectChangedEvent.KIND_CHANGED == event.getKind()
            && MavenProjectChangedEvent.FLAG_DEPENDENCIES == event.getFlags() )
        {
            forceManifestRegeneration( event.getMavenProject().getProject(), monitor );
        }
    }

    protected void forceManifestRegeneration( IProject project, IProgressMonitor monitor )
        throws CoreException
    {
        // touch bundle manifests to force regeneration

        // unfortunately, this does not work. when workspace autobuild is on, project registry is updated
        // synchronously from MavenBuilder. this means that any resource changes by this code are not
        // available when the same MavenBuild runs build participants
        IFile manifest = PDEProjectHelper.getBundleManifest( project );
        if ( manifest != null && manifest.isAccessible() )
        {
            manifest.touch( monitor );
        }

        // this is a less pretty way to force bundle manifest regeneration.
        // the property is checked and reset by the build participant
        project.setSessionProperty( PROP_FORCE_GENERATE, "true" );
    }

    @Override
    public boolean hasConfigurationChanged( IMavenProjectFacade newFacade,
                                            ILifecycleMappingConfiguration oldProjectConfiguration,
                                            MojoExecutionKey key, IProgressMonitor monitor )
    {
        if ( super.hasConfigurationChanged( newFacade, oldProjectConfiguration, key, monitor ) )
        {
            try
            {
                if ( !equalsManifestLocation( newFacade.getMojoExecution( key, monitor ),
                                              oldProjectConfiguration.getMojoExecutionConfiguration( key ) ) )
                {
                    return true;
                }

                forceManifestRegeneration( newFacade.getProject(), monitor );
            }
            catch ( CoreException e )
            {
                return true;
            }
        }

        return false;
    }

    private boolean equalsManifestLocation( MojoExecution mojoExecution, Xpp3Dom oldConfiguration )
    {
        // for now, just compare xml configuration, but ideally, getMetainfPath should be used to determine new manifest
        // location and the result should be compared to the currently configured PDE manifest location.

        if ( mojoExecution == null )
        {
            return true;
        }

        Xpp3Dom configuration = mojoExecution.getConfiguration();

        Xpp3Dom metainf = getManifestLocation( configuration );
        Xpp3Dom oldMetainf = getManifestLocation( oldConfiguration );

        return metainf != null ? metainf.equals( oldMetainf ) : oldMetainf == null;
    }

    protected Xpp3Dom getManifestLocation( Xpp3Dom configuration )
    {
        return configuration != null ? configuration.getChild( "manifestLocation" ) : null;
    }

}
