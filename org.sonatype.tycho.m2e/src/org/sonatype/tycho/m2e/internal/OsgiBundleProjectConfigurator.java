/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.sonatype.tycho.m2e.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.pde.core.project.IBundleProjectService;
import org.eclipse.pde.internal.core.natures.PDE;
import org.maven.ide.eclipse.core.MavenLogger;
import org.maven.ide.eclipse.project.IMavenProjectFacade;
import org.maven.ide.eclipse.project.configurator.AbstractBuildParticipant;
import org.maven.ide.eclipse.project.configurator.AbstractProjectConfigurator;
import org.maven.ide.eclipse.project.configurator.MojoExecutionBuildParticipant;
import org.maven.ide.eclipse.project.configurator.ProjectConfigurationRequest;

@SuppressWarnings( "restriction" )
public class OsgiBundleProjectConfigurator
    extends AbstractProjectConfigurator
{
    public static final String MOJO_GROUP_ID = "org.apache.felix";

    public static final String MOJO_ARTIFACT_ID = "maven-bundle-plugin";

    @Override
    public void configure( ProjectConfigurationRequest request, IProgressMonitor monitor )
        throws CoreException
    {
        IMavenProjectFacade facade = request.getMavenProjectFacade();
        if ( isOsgiBundleProject( facade, monitor ) )
        {
            IProject project = request.getProject();
            addNature( project, PDE.PLUGIN_NATURE, monitor );
            IProjectDescription description = project.getDescription();
            ICommand[] prevBuilders = description.getBuildSpec();
            ArrayList<ICommand> newBuilders = new ArrayList<ICommand>();
            for ( ICommand builder : prevBuilders )
            {
                if ( !builder.getBuilderName().startsWith( "org.eclipse.pde" ) )
                {
                    newBuilders.add( builder );
                }
            }
            description.setBuildSpec( newBuilders.toArray( new ICommand[newBuilders.size()] ) );
            project.setDescription( description, monitor );

            IPath manifestPath = getManifestPath( facade, request.getMavenSession(), monitor );
            if ( manifestPath != null && manifestPath.segmentCount() > 1 )
            {
                IBundleProjectService projectService = Activator.getDefault().getProjectService();
                projectService.setBundleRoot( project, manifestPath.removeLastSegments( 1 ) );
            }
        }
    }

    /**
     * Returns project relative path of generated OSGi bundle manifest
     */
    private IPath getManifestPath( IMavenProjectFacade facade, MavenSession session, IProgressMonitor monitor )
        throws CoreException
    {
        for ( MojoExecution execution : getExecutions( facade, session, monitor ) )
        {
            File location = getParameterValue( "manifestLocation", File.class, session, execution );
            if ( location != null )
            {
                return facade.getProjectRelativePath( location.getAbsolutePath() );
            }
        }

        return null;
    }

    private boolean isOsgiBundleProject( IMavenProjectFacade facade, IProgressMonitor monitor )
        throws CoreException
    {
        List<Plugin> plugins = facade.getMavenProject( monitor ).getBuildPlugins();
        if ( plugins != null )
        {
            for ( Plugin plugin : plugins )
            {
                if ( isMavenBundlePluginMojo( plugin ) && !plugin.getExecutions().isEmpty() )
                {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public AbstractBuildParticipant getBuildParticipant( MojoExecution execution )
    {
        if ( isMavenBundlePluginMojo( execution ) )
        {
            return new MojoExecutionBuildParticipant( execution, false )
            {
                @Override
                public Set<IProject> build( int kind, IProgressMonitor monitor )
                    throws Exception
                {
                    Set<IProject> projects = super.build( kind, monitor );
                    IMavenProjectFacade facade = getMavenProjectFacade();
                    MavenSession session = getSession();
                    IPath manifestPath = getManifestPath( facade, session, monitor );
                    if ( manifestPath != null )
                    {
                        IFile file = facade.getProject().getFile( manifestPath.append( "MANIFEST.MF" ) );
                        file.refreshLocal( IResource.DEPTH_ZERO, monitor );
                    }
                    return projects;
                }
            };
        }

        return null;
    }

    protected boolean isMavenBundlePluginMojo( MojoExecution execution )
    {
        return isMavenBundlePluginMojo( execution.getGroupId(), execution.getArtifactId() );
    }

    private boolean isMavenBundlePluginMojo( Plugin plugin )
    {
        return isMavenBundlePluginMojo( plugin.getGroupId(), plugin.getArtifactId() );
    }

    private boolean isMavenBundlePluginMojo( String groupId, String artifactId )
    {
        return MOJO_GROUP_ID.equals( groupId ) && MOJO_ARTIFACT_ID.equals( artifactId );
    }

    // TODO this has to go to AbstractProjectConfigurator
    private List<MojoExecution> getExecutions( IMavenProjectFacade facade, MavenSession session,
                                               IProgressMonitor monitor )
        throws CoreException
    {
        List<MojoExecution> executions = new ArrayList<MojoExecution>();
        List<Plugin> plugins = facade.getMavenProject( monitor ).getBuildPlugins();
        for ( Plugin plugin : plugins )
        {
            if ( !isMavenBundlePluginMojo( plugin ) )
            {
                continue;
            }

            if ( plugin.getVersion() == null )
            {
                try
                {
                    String version = maven.resolvePluginVersion( plugin.getGroupId(), plugin.getArtifactId(), session );
                    plugin.setVersion( version );
                }
                catch ( CoreException ex )
                {
                    MavenLogger.log( ex );
                    console.logError( "Failed to determine plugin version for " + plugin );
                    continue;
                }
            }

            for ( PluginExecution execution : plugin.getExecutions() )
            {
                for ( String goal : execution.getGoals() )
                {
                    MojoExecution exec = new MojoExecution( plugin, goal, execution.getId() );
                    exec.setConfiguration( (Xpp3Dom) execution.getConfiguration() );
                    executions.add( exec );
                }
            }
        }
        return executions;
    }

}
