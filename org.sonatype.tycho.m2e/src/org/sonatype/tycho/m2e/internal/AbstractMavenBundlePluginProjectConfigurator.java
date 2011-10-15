/*******************************************************************************
 * Copyright (c) 2008-2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.sonatype.tycho.m2e.internal;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.Scanner;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.configurator.AbstractBuildParticipant;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.MojoExecutionBuildParticipant;
import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.BundleException;
import org.sonatype.plexus.build.incremental.BuildContext;

public abstract class AbstractMavenBundlePluginProjectConfigurator
    extends AbstractProjectConfigurator
{
    public static final String MOJO_GROUP_ID = "org.apache.felix";

    public static final String MOJO_ARTIFACT_ID = "maven-bundle-plugin";

    protected static final QualifiedName PROP_FORCE_GENERATE = new QualifiedName( Activator.PLUGIN_ID, "forceGenerate" );

    static boolean isOsgiBundleProject( IMavenProjectFacade facade, IProgressMonitor monitor )
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

    static boolean isMavenBundlePluginMojo( MojoExecution execution )
    {
        return isMavenBundlePluginMojo( execution.getGroupId(), execution.getArtifactId() );
    }

    static boolean isMavenBundlePluginMojo( Plugin plugin )
    {
        return isMavenBundlePluginMojo( plugin.getGroupId(), plugin.getArtifactId() );
    }

    static boolean isMavenBundlePluginMojo( String groupId, String artifactId )
    {
        return MOJO_GROUP_ID.equals( groupId ) && MOJO_ARTIFACT_ID.equals( artifactId );
    }

    static AbstractBuildParticipant getBuildParticipant( MojoExecution execution )
    {
        execution = amendMojoExecution( execution );

        return new MojoExecutionBuildParticipant( execution, true )
        {
            @Override
            public Set<IProject> build( int kind, IProgressMonitor monitor )
                throws Exception
            {
                BuildContext buildContext = getBuildContext();
                IMavenProjectFacade facade = getMavenProjectFacade();
                IProject project = facade.getProject();
                IFile manifest =
                    project.getFolder( getMetainfPath( facade, getSession(), monitor ) ).getFile( "MANIFEST.MF" );

                // regenerate bundle manifest if any of the following is true
                // - full workspace build
                // - PROP_FORCE_GENERATE project session property is set (see the comment below)
                // - generated bundle manifest changed (why?)
                // - files under project build output folder changed
                // - any of included bnd files changed

                boolean generate = IncrementalProjectBuilder.FULL_BUILD == kind;

                // the property is set by OsgiBundleProjectConfigurator.mavenProjectChanged is a workaround for
                // m2e design limitation, which does not allow project configurators trigger resource deltas
                // visible to build participants. See comment in OsgiBundleProjectConfigurator.mavenProjectChanged
                generate =
                    generate || Boolean.parseBoolean( (String) project.getSessionProperty( PROP_FORCE_GENERATE ) );
                // reset FORCE flag so we don't regenerate forever
                project.setSessionProperty( PROP_FORCE_GENERATE, null );

                IResourceDelta delta = getDelta( project );

                generate = generate || isManifestChange( delta, manifest );

                generate = generate || isIncludeChange( buildContext );

                generate = generate || isBuildOutputChange( buildContext, facade.getMavenProject( monitor ) );

                if ( !generate )
                {
                    return null;
                }

                Set<IProject> projects = super.build( kind, monitor );
                manifest.refreshLocal( IResource.DEPTH_INFINITE, monitor ); // refresh parent?
                return projects;
            }

            private boolean isIncludeChange( BuildContext buildContext )
                throws CoreException
            {
                IMaven maven = MavenPlugin.getMaven();

                @SuppressWarnings( "unchecked" )
                Map<String, String> instructions =
                    maven.getMojoParameterValue( getSession(), getMojoExecution(), "instructions", Map.class );

                if ( instructions == null )
                {
                    return false;
                }

                String include = instructions.get( "_include" );
                if ( include == null )
                {
                    return false;
                }

                ManifestElement[] elements;
                try
                {
                    elements = ManifestElement.parseHeader( "_include", include );
                }
                catch ( BundleException e )
                {
                    // assume nothing changed because BND won't be able to parse it either
                    return false;
                }

                for ( ManifestElement element : elements )
                {
                    String path = element.getValueComponents()[0];
                    if ( path.startsWith( "-" ) || path.startsWith( "~" ) )
                    {
                        path = path.substring( 1 );
                    }

                    // this does not detect changes in outside ${project.basedir}

                    if ( buildContext.hasDelta( path ) )
                    {
                        return true;
                    }
                }

                return false;
            }

            private boolean isManifestChange( IResourceDelta delta, IFile manifest )
            {
                return !manifest.isAccessible() || delta.findMember( manifest.getProjectRelativePath() ) != null;
            }

            private boolean isBuildOutputChange( BuildContext buildContext, MavenProject mavenProject )
            {
                Scanner ds = buildContext.newScanner( new File( mavenProject.getBuild().getOutputDirectory() ) );
                ds.scan();
                String[] includedFiles = ds.getIncludedFiles();
                return includedFiles != null && includedFiles.length > 0;
            }
        };
    }

    protected static MojoExecution amendMojoExecution( MojoExecution execution )
    {
        if ( !isMavenBundlePluginMojo( execution ) )
        {
            throw new IllegalArgumentException();
        }

        if ( "bundle".equals( execution.getGoal() ) )
        {
            // do not generate complete bundle. this is both slow and can produce unexpected workspace changes
            // that will trigger unexpected/endless workspace build.
            // we rely on the fact that ManifestPlugin mojo extends BundlePlugin and does not introduce any
            // additional parameters, so can run manifest goal in place of bundle goal.
            MojoDescriptor descriptor = execution.getMojoDescriptor().clone();
            descriptor.setGoal( "manifest" );
            descriptor.setImplementation( "org.apache.felix.bundleplugin.ManifestPlugin" );
            MojoExecution _execution =
                new MojoExecution( execution.getPlugin(), "manifest", "m2e-tycho:" + execution.getExecutionId()
                    + ":manifest" );
            _execution.setConfiguration( execution.getConfiguration() );
            _execution.setMojoDescriptor( descriptor );
            _execution.setLifecyclePhase( execution.getLifecyclePhase() );
            execution = _execution;
        }
        return execution;
    }

    /**
     * Returns project relative path of the <b>folder</b> where the generated manifest is or will be written
     */
    static IPath getMetainfPath( IMavenProjectFacade facade, MavenSession session, IProgressMonitor monitor )
        throws CoreException
    {
        IMaven maven = MavenPlugin.getMaven();
        for ( MojoExecution execution : facade.getMojoExecutions( MOJO_GROUP_ID, MOJO_ARTIFACT_ID, monitor, "manifest",
                                                                  "bundle" ) )
        {
            File location = maven.getMojoParameterValue( session, execution, "manifestLocation", File.class );
            if ( location != null )
            {
                return facade.getProjectRelativePath( location.getAbsolutePath() );
            }
        }

        return null;
    }

}
